package com.autogram.app.ui.phone

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
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
import com.autogram.app.ui.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Debug-only host for repeatable Google Play screenshots with synthetic data. */
@AndroidEntryPoint
class StoreScreenshotActivity : BaseLocaleActivity() {
    @Inject lateinit var database: AutogramDatabase
    @Inject lateinit var conversations: ConversationRepository
    @Inject lateinit var messages: MessageRepository
    @Inject lateinit var quickReplies: QuickReplyRepository
    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { seedSyntheticData() }
            render(intent.getStringExtra(EXTRA_SCREEN).orEmpty())
        }
    }

    private fun render(screen: String) {
        setContent {
            AutogramTheme {
                when (screen) {
                    SCREEN_HISTORY -> HistoryWithPendingTabsScreen(
                        historyViewModel = hiltViewModel(),
                        pendingViewModel = hiltViewModel(),
                        initialTab = HistoryTab.HISTORY,
                        onBack = {}
                    )
                    SCREEN_SETTINGS_PRIVACY -> SettingsScreen(
                        viewModel = hiltViewModel(),
                        onBack = {},
                        initialScrollOffset = 9_000
                    )
                    SCREEN_ONBOARDING_RECORD -> OnboardingScreen(
                        activeTargetFlow = conversations.observeActive(),
                        onFinish = {},
                        onChooseTarget = {},
                        initialStep = 1
                    )
                    else -> MainScreen(
                        onOpenSettings = {},
                        onOpenHistory = {},
                        onOpenHistoryFor = {},
                        onOpenStats = {},
                        onOpenPendingQueue = {},
                        onManagePinned = {},
                        onManageQuickReplies = {}
                    )
                }
            }
        }
    }

    private suspend fun seedSyntheticData() {
        database.clearAllTables()
        val now = System.currentTimeMillis()
        settings.update(
            AppSettings(
                onboardingCompleted = true,
                showPendingBadges = true,
                previewBeforeSend = true,
                summarizeLongMessages = true,
                transcribeIncomingVoice = true
            )
        )

        listOf(
            Conversation(
                id = "store-alex",
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
        ).forEach { conversations.upsert(it) }

        listOf(
            QuickReply("store-reply-1", "On my way", QuickReplyKind.TEXT, "On my way", position = 0),
            QuickReply("store-reply-2", "Call you soon", QuickReplyKind.TEXT, "I'll call you soon", position = 1),
            QuickReply("store-reply-3", "Thanks!", QuickReplyKind.TEXT, "Thanks!", position = 2)
        ).forEach { quickReplies.upsert(it) }

        listOf(
            Message(
                id = "store-message-1",
                conversationId = "store-alex",
                direction = MessageDirection.INCOMING,
                state = MessageState.PLAYED,
                textContent = "I am leaving now and should arrive around 18:30. Shall I pick up anything on the way?",
                createdAt = now - 600_000,
                playedAt = now - 590_000,
                summary = "Alex is arriving around 18:30 and asks whether anything is needed.",
                summaryState = SummaryState.DONE
            ),
            Message(
                id = "store-message-2",
                conversationId = "store-alex",
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
                createdAt = now - 180_000
            ),
            Message(
                id = "store-message-4",
                conversationId = "store-project",
                direction = MessageDirection.INCOMING,
                state = MessageState.PLAYED,
                textContent = "The launch checklist is complete and the final review is scheduled for tomorrow morning.",
                createdAt = now - 300_000,
                playedAt = now - 290_000,
                transcription = "The launch checklist is complete and the final review is scheduled for tomorrow morning.",
                transcriptionState = TranscriptionState.DONE
            )
        ).forEach { messages.insert(it) }
    }

    companion object {
        const val EXTRA_SCREEN: String = "screen"
        const val SCREEN_HOME: String = "home"
        const val SCREEN_HISTORY: String = "history"
        const val SCREEN_SETTINGS_PRIVACY: String = "settings_privacy"
        const val SCREEN_ONBOARDING_RECORD: String = "onboarding_record"
    }
}
