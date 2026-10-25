package com.autogram.app.domain.usecase

import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.infrastructure.telegram.mtproto.TdLibSender
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/** Resolves notification identities before forwarding a read receipt to Telegram. */
class MarkNotificationMessageReadUseCase @Inject constructor(
    private val messages: MessageRepository,
    private val conversations: ConversationRepository,
    private val sender: TdLibSender
) {
    suspend operator fun invoke(messageId: String?, conversationId: String?) {
        if (messageId == null || conversationId == null) return
        val message = messages.getById(messageId) ?: return
        // A stale or mismatched notification must not mark a different chat read.
        if (message.conversationId != conversationId) return
        val conversation = conversations.getById(conversationId) ?: return
        val chatId = conversation.targetChatId.toLongOrNull() ?: return
        val telegramMessageId = message.telegramMessageId ?: return
        val result = sender.send(TdApi.ViewMessages(chatId, longArrayOf(telegramMessageId), null, true))
        check(result is TdApi.Ok) { "Telegram did not acknowledge the read receipt" }
    }
}
