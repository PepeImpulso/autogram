package com.autogram.app.domain.model

enum class MessageState {
    IDLE,
    RECORDING,
    RECORDED,
    VALIDATING,
    DISCARDED,
    QUEUED,
    QUEUED_OFFLINE,        // network/TDLib unavailable when the user hit send; retry when back
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

enum class TranscriptionState {
    NONE,          // not attempted (default for non-voice messages or when disabled)
    PENDING,       // queued for the transcription engine
    IN_PROGRESS,
    DONE,
    FAILED,
    UNAVAILABLE    // on-device engine not installed (e.g. user hasn't downloaded the language pack)
}

enum class SummaryState {
    NONE,
    PENDING,
    IN_PROGRESS,
    DONE,
    FAILED,
    UNAVAILABLE    // engine misconfigured or not present on device
}

enum class SummaryEngineType {
    NONE,           // feature off
    GEMINI_NANO,    // on-device via Google AI Edge (S25+, Pixel 8+)
    LLM_ENDPOINT    // user-configured OpenAI-compatible HTTP endpoint
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
    val quickReplyId: String? = null,
    // Automatic transcription of incoming voice notes. Text that Merlin (or
    // any human) sent as a voice message — filled in by TranscribeVoiceUseCase
    // when settings.transcribeIncomingVoice is on.
    val transcription: String? = null,
    val transcriptionState: TranscriptionState = TranscriptionState.NONE,
    // Automatic 1-2 sentence summary of a long transcription or text message.
    // Produced by SummarizeMessageUseCase when the user has configured an
    // engine (Gemini Nano on-device or an OpenAI-compatible endpoint) and
    // the content is long enough to benefit.
    val summary: String? = null,
    val summaryState: SummaryState = SummaryState.NONE
)

data class Conversation(
    val id: String,
    val targetChatId: String,
    val targetTitle: String? = null,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastMessageAt: Long? = null,
    val topicId: Long? = null,
    val topicName: String? = null,
    val isPinned: Boolean = false,
    val photoLocalPath: String? = null,
    val sortOrder: Int = 0,
    val lastRepliedAt: Long? = null,
    // null = not in any folder (shown in the "All" tab, not in any specific folder tab).
    val folderId: String? = null
)

/**
 * Grouping of pinned circles. Purely a UX affordance — when the user has
 * many circles, folders let them filter the Main grid by a tab. Deleting a
 * folder nulls out `Conversation.folderId` for its members (they're not
 * removed), so a delete is non-destructive.
 */
data class CircleFolder(
    val id: String,
    val name: String,
    // Color tint for the tab label / chip. Stored as ARGB int so the
    // converter doesn't need a separate type; 0 means "use default theme".
    val color: Int = 0,
    val sortOrder: Int = 0
)

enum class QuickReplyKind { TEXT, VOICE }

enum class QuickReplyFilter { ALL, TEXT, VOICE }

data class QuickReply(
    val id: String,
    val label: String,
    val kind: QuickReplyKind = QuickReplyKind.TEXT,
    // Only meaningful when kind == TEXT. Empty for VOICE.
    val textContent: String = "",
    // Only meaningful when kind == VOICE. Absolute path to a persistent
    // .ogg/Opus file under `filesDir/voice_shortcuts/<id>.ogg`.
    val audioFilePath: String? = null,
    val audioDurationMs: Long = 0L,
    val position: Int,
    val isEnabled: Boolean = true,
    // null = follow the primary circle. Non-null = always send to this
    // specific Conversation.id when the shortcut is tapped, ignoring
    // whichever circle happens to be primary at the moment.
    val targetConversationId: String? = null
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
    val languageTag: String = "",
    // Delete the .ogg file of an incoming voice note right after it finishes
    // playing. Saves storage; default on because most users want ephemeral.
    val autoDeletePlayed: Boolean = true,
    // Short vibrations when recording starts/stops. Mirrors the audio cues.
    val hapticEnabled: Boolean = true,
    // Show a bottom sheet with play/retry/send after releasing the record
    // button, instead of firing the send directly.
    val previewBeforeSend: Boolean = false,
    // Gate the app behind fingerprint/face unlock when re-entering.
    val biometricLockEnabled: Boolean = false,
    // Set to true once the user finishes the first-run onboarding wizard.
    val onboardingCompleted: Boolean = false,
    // Automatic on-device transcription of incoming voice notes. Default off
    // so users opt in consciously — the engine downloads language packs on
    // first run which can take a few minutes.
    val transcribeIncomingVoice: Boolean = false,
    // Per-circle pending-reply badges were too noisy for users who don't
    // feel obligated to answer every incoming. Default OFF — turn on only
    // if you want the visual nag.
    val showPendingBadges: Boolean = false,
    // Summarize long incoming messages with a short 1-2 sentence summary.
    // Requires a configured engine (see summaryEngine below). Default OFF.
    val summarizeLongMessages: Boolean = false,
    val summaryEngine: SummaryEngineType = SummaryEngineType.NONE,
    // User-provided OpenAI-compatible endpoint config. Only used when
    // summaryEngine == LLM_ENDPOINT. Never logged; scrubbed from Sentry.
    val summaryLlmEndpoint: String = "",
    val summaryLlmApiKey: String = "",
    val summaryLlmModel: String = "gpt-4o-mini",
    // Which kind of quick-reply shortcuts show in the chip row on the home
    // screen. ALL shows both; user can bias toward just text or just voice.
    val quickReplyFilter: QuickReplyFilter = QuickReplyFilter.ALL,
    // Keep the device's screen on while Autogram is in the foreground —
    // bypasses the system screen-timeout so the user can glance at the
    // app while driving / cooking without it dimming. Default OFF so we
    // don't drain battery for users who don't ask for it.
    val keepScreenOn: Boolean = false,
    // Developer mode — shows an on-screen camera button on the Home
    // screen that captures a screenshot of the app and opens the system
    // share sheet so the user can send it to the developer for
    // bug-report context. Default OFF; turning it on adds visual chrome
    // most users won't want.
    val developerMode: Boolean = false
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
    AUDIO_FOCUS_DENIED,
    /**
     * v1.22.0 — incoming message arrived while the user was recording
     * a voice note. Autoplay defers so playback can't talk over the
     * user mid-record (or contaminate the new recording's audio).
     * Drained after recording ends if [AppSettings.autoPlayReplies] is on.
     */
    USER_RECORDING,
    /**
     * v1.23.7 — another voice / TTS / summary was already playing when
     * this message arrived; the busy-check enqueued it so the queue
     * stays serial. Drained automatically by playNext() when the
     * current playback completes (or the user taps skip-next). Without
     * this reason, the busy-deferred messages were only kept in memory
     * and the Pending Queue tab list (which queries Room) would show
     * empty even though the badge counted them.
     */
    PLAYBACK_BUSY
}
