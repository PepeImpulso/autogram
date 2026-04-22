package com.autogram.app.domain.usecase

import com.autogram.app.domain.model.Message
import com.autogram.app.domain.model.MessageState
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.infrastructure.telegram.mtproto.AuthState
import com.autogram.app.infrastructure.telegram.mtproto.TdLibSender
import com.autogram.app.infrastructure.telegram.mtproto.toForumTopicOrNull
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drains the outbox: messages stuck in QUEUED_OFFLINE / FAILED that haven't
 * exceeded the user's retry budget. Pure use case with no Android Context
 * dependency so it's unit-testable without Robolectric.
 *
 * Called by `RetryWorker` (the WorkManager shell). The worker handles the
 * scheduling + constraints; this class owns the actual send logic so the
 * decision branches (auth ready, conversation gone, content missing,
 * Telegram error → retry vs permanent) can be exercised in isolation.
 */
@Singleton
class OutboxRetryUseCase @Inject constructor(
    private val tdLibClient: TdLibSender,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository
) {

    /**
     * Result the caller (worker) maps to its own Result enum. Kept distinct
     * so the use case isn't coupled to androidx.work.
     */
    enum class Outcome { SUCCESS, RETRY }

    suspend fun runOnce(): Outcome {
        return try {
            if (tdLibClient.authState.value !is AuthState.Ready) {
                Timber.d("OutboxRetry: TDLib not authenticated, rescheduling")
                return Outcome.RETRY
            }
            val maxRetries = settingsRepository.get().maxRetryAttempts
            val outbox = messageRepository.getOutbox(limit = 50, maxRetries = maxRetries)
            if (outbox.isEmpty()) {
                Timber.d("OutboxRetry: outbox empty")
                return Outcome.SUCCESS
            }
            Timber.i("OutboxRetry: processing ${outbox.size} outbox message(s)")
            for (msg in outbox) {
                sendOne(msg, maxRetries)
            }
            Outcome.SUCCESS
        } catch (e: Exception) {
            Timber.e(e, "OutboxRetry crashed, will retry")
            Outcome.RETRY
        }
    }

    private suspend fun sendOne(msg: Message, maxRetries: Int) {
        val target = conversationRepository.getById(msg.conversationId) ?: run {
            Timber.w(
                "OutboxRetry: conversation ${msg.conversationId} gone " +
                "(deleted while queued), message ${msg.id} → PERMANENTLY_FAILED"
            )
            messageRepository.update(
                msg.copy(
                    state = MessageState.PERMANENTLY_FAILED,
                    errorMessage = "Target conversation no longer exists"
                )
            )
            return
        }

        messageRepository.update(msg.copy(state = MessageState.SENDING))
        try {
            val content = buildContent(msg) ?: run {
                Timber.w("OutboxRetry: message ${msg.id} has no sendable content, marking failed")
                messageRepository.update(
                    msg.copy(
                        state = MessageState.PERMANENTLY_FAILED,
                        errorMessage = "No content to send"
                    )
                )
                return
            }
            val result = tdLibClient.send(
                TdApi.SendMessage().apply {
                    chatId = target.targetChatId.toLong()
                    topicId = target.topicId.toForumTopicOrNull()
                    inputMessageContent = content
                }
            )
            when (result) {
                is TdApi.Message -> {
                    messageRepository.update(
                        msg.copy(
                            state = MessageState.SENT,
                            telegramMessageId = result.id,
                            sentAt = System.currentTimeMillis()
                        )
                    )
                    conversationRepository.setLastRepliedAt(target.id, System.currentTimeMillis())
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
        if (msg.textContent != null) {
            return TdApi.InputMessageText().apply {
                text = TdApi.FormattedText(msg.textContent, emptyArray())
            }
        }
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
        messageRepository.update(
            msg.copy(
                state = finalState,
                retryCount = next,
                errorMessage = reason
            )
        )
        Timber.w("OutboxRetry: ${msg.id} attempt $next/$maxRetries failed — $reason → $finalState")
    }
}
