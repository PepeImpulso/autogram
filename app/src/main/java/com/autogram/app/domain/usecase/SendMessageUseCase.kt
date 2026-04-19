package com.autogram.app.domain.usecase

import com.autogram.app.domain.model.*
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.domain.statemachine.MessageStateMachine
import com.autogram.app.infrastructure.audio.AudioRecorder
import com.autogram.app.infrastructure.audio.EmptyMessageFilter
import com.autogram.app.infrastructure.telegram.TelegramApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * Caso de uso principal: grabar, validar, enviar mensaje de voz a Telegram.
 * Gestiona todo el ciclo de vida del mensaje saliente.
 */
class SendMessageUseCase @Inject constructor(
    private val audioRecorder: AudioRecorder,
    private val emptyMessageFilter: EmptyMessageFilter,
    private val telegramApiClient: TelegramApiClient,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val stateMachine: MessageStateMachine
) {
    private val _currentStatus = MutableStateFlow<SendStatus>(SendStatus.Idle)
    val currentStatus: StateFlow<SendStatus> = _currentStatus

    private var currentMessage: Message? = null

    sealed class SendStatus {
        object Idle : SendStatus()
        object Recording : SendStatus()
        object Validating : SendStatus()
        object Sending : SendStatus()
        data class Sent(val messageId: String) : SendStatus()
        data class Error(val message: String) : SendStatus()
        data class Discarded(val reason: String) : SendStatus()
    }

    /**
     * Inicia la grabación de audio.
     * @param context Contexto de la aplicación para acceder al directorio de archivos
     */
    suspend fun startRecording(context: android.content.Context): Result<Message> {
        if (audioRecorder.isRecording()) {
            return Result.failure(IllegalStateException("Ya hay una grabación en curso"))
        }

        val conversation = conversationRepository.getActive()
            ?: return Result.failure(IllegalStateException("No hay conversación activa. Configura el bot en Ajustes."))

        val settings = settingsRepository.get()

        // Crear archivo de salida
        val audioDir = File(context.cacheDir, "audio_outgoing").apply { mkdirs() }
        val audioFile = File(audioDir, "${UUID.randomUUID()}.ogg")

        // Crear mensaje en estado RECORDING
        val message = Message(
            id = UUID.randomUUID().toString(),
            conversationId = conversation.id,
            direction = MessageDirection.OUTGOING,
            state = MessageState.RECORDING,
            audioFilePath = audioFile.absolutePath,
            createdAt = System.currentTimeMillis()
        )
        messageRepository.insert(message)
        currentMessage = message

        // Iniciar grabación
        val result = audioRecorder.start(audioFile)
        return if (result.isSuccess) {
            _currentStatus.value = SendStatus.Recording
            Result.success(message)
        } else {
            val errorMsg = result.exceptionOrNull()?.message ?: "Error al iniciar grabación"
            messageRepository.update(message.copy(state = MessageState.FAILED, errorMessage = errorMsg))
            _currentStatus.value = SendStatus.Error(errorMsg)
            Result.failure(result.exceptionOrNull()!!)
        }
    }

    /**
     * Detiene la grabación, valida el audio y envía a Telegram.
     */
    suspend fun stopAndSend(): Result<Message> {
        val message = currentMessage ?: return Result.failure(IllegalStateException("No hay grabación en curso"))

        // Detener grabación
        val audioFile = audioRecorder.stop()
        if (audioFile == null || !audioFile.exists()) {
            val errorMsg = "No se pudo guardar el audio"
            messageRepository.update(message.copy(state = MessageState.FAILED, errorMessage = errorMsg))
            _currentStatus.value = SendStatus.Error(errorMsg)
            currentMessage = null
            return Result.failure(Exception(errorMsg))
        }

        // Transición a RECORDED → VALIDATING
        transition(message, MessageState.RECORDED)
        transition(message, MessageState.VALIDATING)
        _currentStatus.value = SendStatus.Validating

        // Validar audio (duración mínima, silencio)
        val settings = settingsRepository.get()
        val filterResult = emptyMessageFilter.validate(audioFile, settings.minRecordingDurationMs)
        if (!filterResult.accepted) {
            transition(message, MessageState.DISCARDED)
            _currentStatus.value = SendStatus.Discarded(filterResult.reason ?: "Mensaje vacío")
            // Limpiar archivo
            audioFile.delete()
            transition(message, MessageState.IDLE)
            currentMessage = null
            return Result.failure(Exception("Mensaje descartado: ${filterResult.reason}"))
        }

        // Transición a QUEUED → SENDING
        transition(message, MessageState.QUEUED)
        _currentStatus.value = SendStatus.Sending
        transition(message, MessageState.SENDING)

        // Enviar a Telegram
        val conversation = conversationRepository.getById(message.conversationId)!!
        telegramApiClient.configure(conversation.botToken)

        val sendResult = telegramApiClient.sendVoice(
            chatId = conversation.targetChatId,
            voiceFilePath = audioFile.absolutePath
        )

        return if (sendResult.isSuccess) {
            val telegramMsg = sendResult.getOrThrow()
            val sentMessage = message.copy(
                state = MessageState.SENT,
                telegramMessageId = telegramMsg.message_id,
                sentAt = System.currentTimeMillis(),
                audioDurationMs = emptyMessageFilter.getDurationMs(audioFile)
            )
            messageRepository.update(sentMessage)
            _currentStatus.value = SendStatus.Sent(sentMessage.id)
            currentMessage = null
            Result.success(sentMessage)
        } else {
            val error = sendResult.exceptionOrNull()?.message ?: "Error de envío"
            val failedMessage = message.copy(
                state = MessageState.FAILED,
                errorMessage = error,
                retryCount = message.retryCount + 1
            )
            messageRepository.update(failedMessage)
            _currentStatus.value = SendStatus.Error(error)
            currentMessage = null
            Result.failure(Exception(error))
        }
    }

    /**
     * Envía un mensaje de texto rápido (quick reply).
     */
    suspend fun sendQuickReply(text: String): Result<Message> {
        val conversation = conversationRepository.getActive()
            ?: return Result.failure(IllegalStateException("No hay conversación activa"))

        telegramApiClient.configure(conversation.botToken)

        val sendResult = telegramApiClient.sendMessage(
            chatId = conversation.targetChatId,
            text = text
        )

        return if (sendResult.isSuccess) {
            val telegramMsg = sendResult.getOrThrow()
            val message = Message(
                id = UUID.randomUUID().toString(),
                conversationId = conversation.id,
                direction = MessageDirection.OUTGOING,
                state = MessageState.SENT,
                telegramMessageId = telegramMsg.message_id,
                textContent = text,
                sentAt = System.currentTimeMillis(),
                createdAt = System.currentTimeMillis()
            )
            messageRepository.insert(message)
            Result.success(message)
        } else {
            Result.failure(sendResult.exceptionOrNull()!!)
        }
    }

    /**
     * Cancela la grabación actual sin enviar.
     */
    suspend fun cancelRecording() {
        audioRecorder.stop()
        currentMessage?.let { msg ->
            msg.audioFilePath?.let { File(it).delete() }
            messageRepository.update(msg.copy(state = MessageState.DISCARDED))
        }
        currentMessage = null
        _currentStatus.value = SendStatus.Idle
    }

    /**
     * Reintenta el envío de un mensaje fallido.
     */
    suspend fun retryMessage(messageId: String): Result<Message> {
        val message = messageRepository.getById(messageId)
            ?: return Result.failure(Exception("Mensaje no encontrado"))

        if (message.state != MessageState.FAILED) {
            return Result.failure(IllegalStateException("Solo se pueden reintentar mensajes fallidos"))
        }

        val settings = settingsRepository.get()
        if (message.retryCount >= settings.maxRetryAttempts) {
            messageRepository.update(message.copy(state = MessageState.PERMANENTLY_FAILED))
            return Result.failure(Exception("Se agotaron los reintentos"))
        }

        val conversation = conversationRepository.getById(message.conversationId)!!
        telegramApiClient.configure(conversation.botToken)

        transition(message, MessageState.QUEUED)
        transition(message, MessageState.SENDING)

        val result = if (message.audioFilePath != null) {
            telegramApiClient.sendVoice(conversation.targetChatId, message.audioFilePath)
        } else if (message.textContent != null) {
            telegramApiClient.sendMessage(conversation.targetChatId, message.textContent)
        } else {
            return Result.failure(Exception("Mensaje sin contenido"))
        }

        return if (result.isSuccess) {
            val sent = message.copy(
                state = MessageState.SENT,
                telegramMessageId = result.getOrThrow().message_id,
                sentAt = System.currentTimeMillis(),
                errorMessage = null
            )
            messageRepository.update(sent)
            Result.success(sent)
        } else {
            val failed = message.copy(
                state = MessageState.FAILED,
                errorMessage = result.exceptionOrNull()?.message,
                retryCount = message.retryCount + 1
            )
            messageRepository.update(failed)
            Result.failure(result.exceptionOrNull()!!)
        }
    }

    private suspend fun transition(message: Message, target: MessageState) {
        stateMachine.transition(message.state, target).onSuccess {
            messageRepository.update(message.copy(state = it))
        }.onFailure {
            Timber.e("Transición inválida: ${message.state} → $target")
        }
    }

    fun reset() {
        _currentStatus.value = SendStatus.Idle
        currentMessage = null
    }
}
