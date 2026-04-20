package com.autogram.app.infrastructure.persistence.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.MessageState

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
    val quickReplyId: String? = null
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val targetChatId: String,
    val targetTitle: String? = null,
    val botToken: String,
    val isActive: Boolean = true,
    val createdAt: Long,
    val lastMessageAt: Long? = null,
    // Phase 2b: forum topics. null = no topic (private chat / regular group / forum default).
    val topicId: Long? = null,
    val topicName: String? = null
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
    val textContent: String,
    val position: Int,
    val isEnabled: Boolean = true
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
    val autoPlayReplies: Boolean = true
)
