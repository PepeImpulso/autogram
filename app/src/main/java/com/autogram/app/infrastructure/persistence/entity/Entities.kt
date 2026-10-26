package com.autogram.app.infrastructure.persistence.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.MessageState
import com.autogram.app.domain.model.SummaryEngineType
import com.autogram.app.domain.model.SummaryState
import com.autogram.app.domain.model.TranscriptionState

/**
 * Entidad Room para mensajes.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversationId"),
        Index("state"),
        Index("createdAt")
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val direction: MessageDirection,
    val state: MessageState,
    val telegramMessageId: Long? = null,
    val audioFilePath: String? = null,
    val audioDurationMs: Long? = null,
    val textContent: String? = null,
    val createdAt: Long,
    val sentAt: Long? = null,
    val playedAt: Long? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val quickReplyId: String? = null,
    val transcription: String? = null,
    val transcriptionState: TranscriptionState = TranscriptionState.NONE,
    val summary: String? = null,
    val summaryState: SummaryState = SummaryState.NONE,
    val spokenText: String? = null
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val targetChatId: String,
    val targetTitle: String? = null,
    // `isActive` marks the primary conversation (Merlin — big circle on main screen).
    // Exactly one row should have isActive=true at a time; the receiver listens on this one.
    val isActive: Boolean = true,
    val createdAt: Long,
    val lastMessageAt: Long? = null,
    val topicId: Long? = null,
    val topicName: String? = null,
    // Shown as a circle on the main screen. The primary is implicitly pinned.
    val isPinned: Boolean = false,
    // Absolute path to a user-picked background image for the circle. null = fallback tint.
    val photoLocalPath: String? = null,
    // Ordering of secondary (non-primary) circles on the main screen.
    val sortOrder: Int = 0,
    // Timestamp of the last *outgoing* message we sent to this target. Used to
    // compute the "pending-to-reply" badge: count of INCOMING messages with
    // createdAt > lastRepliedAt. Null = user has never replied to this circle.
    val lastRepliedAt: Long? = null,
    // Hide secondary circles from Home without unpinning or deleting them.
    // Primary rows ignore this and are always shown.
    val showOnHome: Boolean = true,
    // Optional grouping folder. Null = not in any folder — shown under the
    // "All" tab on Main but not filtered into any specific folder tab.
    val folderId: String? = null
)

@Entity(tableName = "circle_folders")
data class CircleFolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: Int = 0,
    val sortOrder: Int = 0
)

@Entity(
    tableName = "delivery_attempts",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("messageId")]
)
data class DeliveryAttemptEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val attemptNumber: Int,
    val attemptedAt: Long,
    val success: Boolean,
    val errorMessage: String? = null,
    val httpStatusCode: Int? = null
)

@Entity(
    tableName = "pending_playbacks",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("messageId")]
)
data class PendingPlaybackEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val enqueuedAt: Long,
    val reason: com.autogram.app.domain.model.PendingReason,
    val playedAt: Long? = null
)

@Entity(tableName = "quick_replies")
data class QuickReplyEntity(
    @PrimaryKey val id: String,
    val label: String,
    // "TEXT" | "VOICE" — stored as string so we can add new kinds without
    // a migration. Defaults keep every pre-v14 row as TEXT.
    val kind: String = "TEXT",
    val textContent: String = "",
    val audioFilePath: String? = null,
    val audioDurationMs: Long = 0L,
    val position: Int,
    val isEnabled: Boolean = true,
    // null = follow the primary circle (legacy behaviour). Non-null pins
    // this shortcut to a specific Conversation.id so tapping it always
    // sends to that contact regardless of which one is primary on Home.
    val targetConversationId: String? = null
)

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val activeConversationId: String? = null,
    val retentionDays: Int = 30,
    val silentMode: Boolean = false,
    val minRecordingDurationMs: Long = 1500,
    val maxRecordingDurationMs: Long = 60000,
    val pollingIntervalMs: Long = 3000,
    val maxRetryAttempts: Int = 3,
    val autoPlayReplies: Boolean = true,
    val playbackSpeed: Float = 1.25f,
    val recordingCuesVolume: Int = 50,
    val languageTag: String = "",
    val autoDeletePlayed: Boolean = true,
    val hapticEnabled: Boolean = true,
    val previewBeforeSend: Boolean = false,
    val biometricLockEnabled: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val transcribeIncomingVoice: Boolean = false,
    val showPendingBadges: Boolean = false,
    val summarizeLongMessages: Boolean = false,
    val summaryEngine: SummaryEngineType = SummaryEngineType.NONE,
    val summaryLlmEndpoint: String = "",
    val summaryLlmApiKey: String = "",
    val summaryLlmModel: String = "gpt-4o-mini",
    // "ALL" | "TEXT" | "VOICE" — persisted as string for the same reason
    // kind is persisted as string on quick_replies.
    val quickReplyFilter: String = "ALL",
    val showQuickReplyFilters: Boolean = true,
    val showQuickReplyShortcuts: Boolean = true,
    val keepScreenOn: Boolean = false,
    val developerMode: Boolean = false,
    // v1.24.0 — see [AppSettings.audioFocusMode]. Persisted as enum name
    // so adding a new mode is backward-compatible.
    val audioFocusMode: String = "TRANSIENT_MAY_DUCK",
    val ttsAsNavigationPrompt: Boolean = false,
    val carGridButtons: Boolean = false
)
