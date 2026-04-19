package com.autogram.app.infrastructure.retry

import android.content.Context
import androidx.work.*
import com.autogram.app.domain.model.MessageState
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.infrastructure.telegram.TelegramApiClient
import dagger.hilt.android.HiltWorker
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Worker de WorkManager para reintentar el envío de mensajes fallidos.
 * Implementa backoff exponencial: 30s, 60s, 120s.
 */
@HiltWorker
class RetryWorker @Inject constructor(
    context: Context,
    workerParams: WorkerParameters,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val telegramApiClient: TelegramApiClient
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val KEY_MESSAGE_ID = "message_id"

        /**
         * Encola un reintento para un mensaje fallido.
         */
        fun enqueue(context: Context, messageId: String, retryCount: Int) {
            val delayMs = calculateDelay(retryCount)
            val workRequest = OneTimeWorkRequestBuilder<RetryWorker>()
                .setInputData(workDataOf(KEY_MESSAGE_ID to messageId))
                .setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30_000L,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "retry_$messageId",
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )
            Timber.d("Reintento encolado para mensaje $messageId (intento ${retryCount + 1}, delay=${delayMs}ms)")
        }

        /**
         * Calcula el delay de backoff exponencial.
         */
        private fun calculateDelay(retryCount: Int): Long {
            return when (retryCount) {
                0 -> 30_000L  // 30 segundos
                1 -> 60_000L  // 1 minuto
                else -> 120_000L // 2 minutos
            }
        }
    }

    override suspend fun doWork(): Result {
        val messageId = inputData.getString(KEY_MESSAGE_ID) ?: run {
            Timber.e("RetryWorker: messageId no proporcionado")
            return Result.failure()
        }

        val message = messageRepository.getById(messageId) ?: run {
            Timber.e("RetryWorker: mensaje $messageId no encontrado")
            return Result.failure()
        }

        if (message.state != MessageState.FAILED) {
            Timber.d("RetryWorker: mensaje $messageId ya no está en estado FAILED (estado: ${message.state})")
            return Result.success()
        }

        val settings = settingsRepository.get()
        if (message.retryCount >= settings.maxRetryAttempts) {
            Timber.w("RetryWorker: mensaje $messageId agotó reintentos (${message.retryCount})")
            messageRepository.update(message.copy(state = MessageState.PERMANENTLY_FAILED))
            return Result.failure()
        }

        val conversation = conversationRepository.getById(message.conversationId) ?: run {
            Timber.e("RetryWorker: conversación ${message.conversationId} no encontrada")
            return Result.failure()
        }

        return try {
            telegramApiClient.configure(conversation.botToken)

            val sendResult = if (message.audioFilePath != null) {
                val file = File(message.audioFilePath)
                if (!file.exists()) {
                    messageRepository.update(message.copy(
                        state = MessageState.PERMANENTLY_FAILED,
                        errorMessage = "Archivo de audio eliminado"
                    ))
                    return Result.failure()
                }
                telegramApiClient.sendVoice(conversation.targetChatId, message.audioFilePath)
            } else if (message.textContent != null) {
                telegramApiClient.sendMessage(conversation.targetChatId, message.textContent)
            } else {
                return Result.failure()
            }

            if (sendResult.isSuccess) {
                val telegramMsg = sendResult.getOrThrow()
                messageRepository.update(message.copy(
                    state = MessageState.SENT,
                    telegramMessageId = telegramMsg.message_id,
                    sentAt = System.currentTimeMillis(),
                    errorMessage = null
                ))
                Timber.i("RetryWorker: mensaje $messageId enviado correctamente")
                Result.success()
            } else {
                val error = sendResult.exceptionOrNull()?.message ?: "Error desconocido"
                messageRepository.update(message.copy(
                    state = MessageState.FAILED,
                    errorMessage = error,
                    retryCount = message.retryCount + 1
                ))

                // Encolar siguiente reintento si quedan intentos
                if (message.retryCount + 1 < settings.maxRetryAttempts) {
                    enqueue(applicationContext, messageId, message.retryCount + 1)
                }

                Result.retry()
            }
        } catch (e: Exception) {
            Timber.e(e, "RetryWorker: error reintentando mensaje $messageId")
            messageRepository.update(message.copy(
                state = MessageState.FAILED,
                errorMessage = e.message,
                retryCount = message.retryCount + 1
            ))
            Result.retry()
        }
    }
}
