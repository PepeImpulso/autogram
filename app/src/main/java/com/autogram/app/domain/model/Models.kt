package com.autogram.app.domain.model

enum class MessageState {
    IDLE,
    RECORDING,
    RECORDED,
    VALIDATING,
    DISCARDED,
    QUEUED,
    SENDING,
    SENT,
    FAILED,
    PERMANENTLY_FAILED,
    RECEIVED,
    PENDING_PLAYBACK,
    PLAYING,
    PAUSED,
    PLAYED,
    ARCHIVED
}

enum class MessageDirection {
    OUTGOING,
    INCOMING
}

data class Message(
    val id: String,
    val conversationId: String,
    val direction: MessageDirection,
    val state: MessageState,
    val telegramMessageId: Long? = null,
    val audioFilePath: String? = null,
    val audioDurationMs: Long? = null,
    val textContent: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val sentAt: Long? = null,
    val playedAt: Long? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val quickReplyId: String? = null
)

data class Conversation(
    val id: String,
    val targetChatId: String,
    val targetTitle: String? = null,
    val botToken: String,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastMessageAt: Long? = null,
    val topicId: Long? = null,
    val topicName: String? = null,
    val isPinned: Boolean = false,
    val photoLocalPath: String? = null,
    val sortOrder: Int = 0,
    val lastRepliedAt: Long? = null
)

data class QuickReply(
    val id: String,
    val label: String,
    val textContent: String,
    val position: Int,
    val isEnabled: Boolean = true
)

data class AppSettings(
    val id: Int = 1,
    val activeConversationId: String? = null,
    val retentionDays: Int = 30,
    val silentMode: Boolean = false,
    val minRecordingDurationMs: Long = 1500,
    val maxRecordingDurationMs: Long = 60000,
    val pollingIntervalMs: Long = 3000,
    val maxRetryAttempts: Int = 3,
    val autoPlayReplies: Boolean = true,
    // Speed multiplier applied to incoming voice playback. 1.0 = unchanged.
    // Default 1.25 — most people already listen to podcasts faster than 1x.
    val playbackSpeed: Float = 1.25f,
    // Volume of the start/end recording beeps, 0..100. 0 disables them entirely.
    val recordingCuesVolume: Int = 50,
    // BCP-47 language tag for the UI. Empty string means "follow system".
    val languageTag: String = ""
)

data class DeliveryAttempt(
    val id: String,
    val messageId: String,
    val attemptNumber: Int,
    val attemptedAt: Long = System.currentTimeMillis(),
    val success: Boolean,
    val errorMessage: String? = null,
    val httpStatusCode: Int? = null
)

data class PendingPlayback(
    val id: String,
    val messageId: String,
    val enqueuedAt: Long = System.currentTimeMillis(),
    val reason: PendingReason,
    val playedAt: Long? = null
)

enum class PendingReason {
    SILENT_MODE,
    IN_CALL,
    USER_PAUSED,
    AUDIO_FOCUS_DENIED
}
