package com.autogram.app.infrastructure.retry

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.MessageState
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.infrastructure.telegram.mtproto.AuthState
import com.autogram.app.infrastructure.telegram.mtproto.TdLibClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Drains the outbox — messages in state QUEUED_OFFLINE or FAILED — whenever
 * network + TDLib auth come back. Triggered automatically:
 *
 *   - Once, at process start via [enqueueOnce], so pending messages from the
 *     previous session go out as soon as the app opens.
 *   - By [enqueueOnce] again whenever a new QUEUED_OFFLINE message is written
 *     (the use case calls it after persistQueuedOffline).
 *   - By WorkManager's own network-restored trigger via setConstraints().
 *
 * Each message is retried up to `settings.maxRetryAttempts` before being
 * marked PERMANENTLY_FAILED and handed back to the user's attention.
 */
@HiltWorker
class RetryWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val tdLibClient: TdLibClient,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            if (tdLibClient.authState.value !is AuthState.Ready) {
                // Nothing to do without auth; WorkManager will retry later.
                Timber.d("RetryWorker: TDLib not authenticated, rescheduling")
                return Result.retry()
            }
            val maxRetries = settingsRepository.get().maxRetryAttempts
            val outbox = messageRepository.getOutbox(limit = 50, maxRetries = maxRetries)
            if (outbox.isEmpty()) {
                Timber.d("RetryWorker: outbox empty")
                return Result.success()
            }
            Timber.i("RetryWorker: processing ${outbox.size} outbox message(s)")
            for (msg in outbox) {
                sendOne(msg, maxRetries)
            }
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "RetryWorker crashed, will retry")
            Result.retry()
        }
    }

    private suspend fun sendOne(msg: Message, maxRetries: Int) {
        val target = conversationRepository.getById(msg.conversationId) ?: run {
            // Audit-MED-12: permanent fail (not retry) when the target chat
            // was deleted while the message was queued. Logging explicitly
            // so a user reporting "message stuck" finds the cause in logcat.
            Timber.w("RetryWorker: conversation ${msg.conversationId} gone (deleted while queued), message ${msg.id} → PERMANENTLY_FAILED")
            messageRepository.update(msg.copy(
                state = MessageState.PERMANENTLY_FAILED,
                errorMessage = "Target conversation no longer exists"
            ))
            return
        }

        messageRepository.update(msg.copy(state = MessageState.SENDING))
        try {
            val content = buildContent(msg) ?: run {
                Timber.w("RetryWorker: message ${msg.id} has no sendable content, marking failed")
                messageRepository.update(msg.copy(
                    state = MessageState.PERMANENTLY_FAILED,
                    errorMessage = "No content to send"
                ))
                return
            }
            val result = tdLibClient.send(
                TdApi.SendMessage().apply {
                    chatId = target.targetChatId.toLong()
                    topicId = target.topicId?.let { TdApi.MessageTopicForum(it.toInt()) }
                    inputMessageContent = content
                }
            )
            when (result) {
                is TdApi.Message -> {
                    messageRepository.update(msg.copy(
                        state = MessageState.SENT,
                        telegramMessageId = result.id,
                        sentAt = System.currentTimeMillis()
                    ))
                    conversationRepository.setLastRepliedAt(target.id, System.currentTimeMillis())
                    // Outbox audios are safe to drop now that TDLib has a copy.
                    msg.audioFilePath?.let { path ->
                        runCatching {
                            val f = File(path)
                            if (f.exists() && f.absolutePath.contains("/audio_outbox/")) f.delete()
                        }
                    }
                }
                is TdApi.Error -> failAttempt(msg, "Telegram ${result.code}: ${result.message}", maxRetries)
                else -> failAttempt(msg, "Unexpected TDLib response: ${result.javaClass.simpleName}", maxRetries)
            }
        } catch (e: Exception) {
            failAttempt(msg, e.message ?: "send error", maxRetries)
        }
    }

    private fun buildContent(msg: Message): TdApi.InputMessageContent? {
        // Text quick reply.
        if (msg.textContent != null) {
            return TdApi.InputMessageText().apply {
                text = TdApi.FormattedText(msg.textContent, emptyArray())
            }
        }
        // Voice note — file must still exist on disk.
        val path = msg.audioFilePath ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return TdApi.InputMessageVoiceNote().apply {
            voiceNote = TdApi.InputFileLocal(path)
            duration = ((msg.audioDurationMs ?: 0L) / 1000).toInt()
        }
    }

    private suspend fun failAttempt(msg: Message, reason: String, maxRetries: Int) {
        val next = msg.retryCount + 1
        val finalState = if (next >= maxRetries) MessageState.PERMANENTLY_FAILED
                         else MessageState.FAILED
        messageRepository.update(msg.copy(
            state = finalState,
            retryCount = next,
            errorMessage = reason
        ))
        Timber.w("RetryWorker: ${msg.id} attempt $next/$maxRetries failed — $reason → $finalState")
    }

    companion object {
        const val UNIQUE_NAME = "outbox_retry"

        /**
         * Kick a one-shot attempt. Call after enqueueing a QUEUED_OFFLINE message
         * or from Application.onCreate to drain pending outbox on launch.
         */
        fun enqueueOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<RetryWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }
    }
}
