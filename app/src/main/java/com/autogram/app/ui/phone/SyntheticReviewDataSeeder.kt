package com.autogram.app.ui.phone

import com.autogram.app.domain.model.AppSettings
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.MessageState
import com.autogram.app.domain.model.QuickReply
import com.autogram.app.domain.model.QuickReplyKind
import com.autogram.app.domain.model.SummaryState
import com.autogram.app.domain.model.TranscriptionState
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.QuickReplyRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.infrastructure.persistence.AutogramDatabase
import javax.inject.Inject

/** Seeds reviewer-safe data for screenshots and the Google Play demo account. */
class SyntheticReviewDataSeeder @Inject constructor(
    private val database: AutogramDatabase,
    private val conversations: ConversationRepository,
    private val messages: MessageRepository,
    private val quickReplies: QuickReplyRepository,
    private val settings: SettingsRepository
) {
    suspend fun seed(clearExisting: Boolean) {
        if (clearExisting) database.clearAllTables()
        val now = System.currentTimeMillis()
        val baseSettings = if (clearExisting) AppSettings() else settings.get()
        settings.update(
            baseSettings.copy(
                onboardingCompleted = true,
                showPendingBadges = true,
                previewBeforeSend = true,
                summarizeLongMessages = true,
                transcribeIncomingVoice = true
            )
        )

        syntheticConversations(now).forEach { conversations.upsert(it) }
        syntheticQuickReplies().forEach { quickReplies.upsert(it) }
        syntheticMessages(now).forEach { messages.insert(it) }
    }

    companion object {
        const val PRIMARY_CONVERSATION_ID: String = "store-alex"

        fun primaryConversation(now: Long = System.currentTimeMillis()): Conversation =
            syntheticConversations(now).first()

        fun playbackMessage(now: Long = System.currentTimeMillis()): Message =
            syntheticMessages(now).first().copy(
                summary = null,
                textContent = "This synthetic Autogram message demonstrates foreground media playback. " +
                    "Audio continues while the app is in the background so hands-free messages remain audible " +
                    "while driving. The user can pause or stop playback at any time from Autogram or the " +
                    "vehicle media controls. No private Telegram account or external message is used."
            )

        private fun syntheticConversations(now: Long): List<Conversation> = listOf(
            Conversation(
                id = PRIMARY_CONVERSATION_ID,
                targetChatId = "10001",
                targetTitle = "Alex Morgan",
                isActive = true,
                isPinned = true,
                lastMessageAt = now - 60_000,
                sortOrder = 0
            ),
            Conversation(
                id = "store-family",
                targetChatId = "10002",
                targetTitle = "Family",
                isActive = false,
                isPinned = true,
                lastMessageAt = now - 120_000,
                sortOrder = 1
            ),
            Conversation(
                id = "store-project",
                targetChatId = "10003",
                targetTitle = "Project Atlas",
                topicId = 42,
                topicName = "Launch",
                isActive = false,
                isPinned = true,
                lastMessageAt = now - 180_000,
                sortOrder = 2
            )
        )

        private fun syntheticQuickReplies(): List<QuickReply> = listOf(
            QuickReply("store-reply-1", "On my way", QuickReplyKind.TEXT, "On my way", position = 0),
            QuickReply("store-reply-2", "Call you soon", QuickReplyKind.TEXT, "I'll call you soon", position = 1),
            QuickReply("store-reply-3", "Thanks!", QuickReplyKind.TEXT, "Thanks!", position = 2)
        )

        private fun syntheticMessages(now: Long): List<Message> = listOf(
            Message(
                id = "store-message-1",
                conversationId = PRIMARY_CONVERSATION_ID,
                direction = MessageDirection.INCOMING,
                state = MessageState.PLAYED,
                textContent = "I am leaving now and should arrive around 18:30. Shall I pick up anything on the way?",
                telegramMessageId = 7_001,
                createdAt = now - 600_000,
                playedAt = now - 590_000,
                summary = "Alex is arriving around 18:30 and asks whether anything is needed.",
                summaryState = SummaryState.DONE
            ),
            Message(
                id = "store-message-2",
                conversationId = PRIMARY_CONVERSATION_ID,
                direction = MessageDirection.OUTGOING,
                state = MessageState.SENT,
                textContent = "Perfect, see you soon.",
                createdAt = now - 540_000,
                sentAt = now - 535_000
            ),
            Message(
                id = "store-message-3",
                conversationId = "store-family",
                direction = MessageDirection.INCOMING,
                state = MessageState.RECEIVED,
                textContent = "Dinner is ready at eight. Everyone can make it.",
                telegramMessageId = 7_003,
                createdAt = now - 180_000
            ),
            Message(
                id = "store-message-4",
                conversationId = "store-project",
                direction = MessageDirection.INCOMING,
                state = MessageState.PLAYED,
                textContent = "The launch checklist is complete and the final review is scheduled for tomorrow morning.",
                telegramMessageId = 7_004,
                createdAt = now - 300_000,
                playedAt = now - 290_000,
                transcription = "The launch checklist is complete and the final review is scheduled for tomorrow morning.",
                transcriptionState = TranscriptionState.DONE
            )
        )
    }
}
