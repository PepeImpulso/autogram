package com.autogram.app.ui.phone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.QuickReplyRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.domain.model.CircleFolder
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.QuickReply
import com.autogram.app.domain.model.QuickReplyFilter
import com.autogram.app.domain.model.QuickReplyKind
import com.autogram.app.domain.repository.CircleFolderRepository
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.domain.usecase.SendVoiceMtprotoUseCase
import com.autogram.app.domain.usecase.SuggestQuickReplyUseCase
import com.autogram.app.infrastructure.platform.ConnectivityObserver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val sendMessageUseCase: SendVoiceMtprotoUseCase,
    private val playReplyUseCase: PlayReplyUseCase,
    private val quickReplyRepository: QuickReplyRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val settingsRepository: SettingsRepository,
    private val folderRepository: CircleFolderRepository,
    private val suggestQuickReplyUseCase: SuggestQuickReplyUseCase,
    connectivityObserver: ConnectivityObserver
) : AndroidViewModel(application) {

    val isOnline: StateFlow<Boolean> = connectivityObserver.online

    val folders: StateFlow<List<CircleFolder>> =
        folderRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // null = "All" tab — show every pinned circle. Non-null = filter to
    // circles whose folderId matches. Kept in the VM so the selection
    // survives bottom-nav round-trips within MainActivity.
    private val _selectedFolderId = MutableStateFlow<String?>(null)
    val selectedFolderId: StateFlow<String?> = _selectedFolderId.asStateFlow()

    fun selectFolder(folderId: String?) {
        _selectedFolderId.value = folderId
    }

    // Init block lives at the bottom of the constructor once `suggestedChips`
    // and `activeTargetId` are declared.

    val showPendingBadges: StateFlow<Boolean> =
        settingsRepository.observe()
            .map { it.showPendingBadges }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Ephemeral suggestion chips. Recomputed whenever a new INCOMING message
    // (voice with transcription, or text) arrives on the active conversation.
    // Cleared when the user sends a chip (tap), sends a voice reply, or
    // after [SUGGESTION_TTL_MS] of inactivity.
    private val _suggestedChips = MutableStateFlow<List<SuggestQuickReplyUseCase.Suggestion>>(emptyList())
    val suggestedChips: StateFlow<List<SuggestQuickReplyUseCase.Suggestion>> = _suggestedChips.asStateFlow()
    private var lastSuggestionSourceId: String? = null

    val sendStatus: StateFlow<SendVoiceMtprotoUseCase.SendStatus> = sendMessageUseCase.status
    val playbackStatus: StateFlow<PlayReplyUseCase.PlaybackStatus> = playReplyUseCase.playbackStatus
    val activeTargetId: StateFlow<String?> = sendMessageUseCase.activeTargetId

    /**
     * Most-recent incoming voice with a playable audio file, across
     * ALL conversations. Feeds the always-visible top-right "play
     * last original" toggle in the HeroHeader.
     *
     * Backed by a dedicated `LIMIT 1` DAO query rather than filtering
     * `observeAllAcrossConversations` — the latter is capped at the
     * most recent 500 rows to keep UI latency flat, which for power
     * users sending many text shortcuts can push the last incoming
     * voice off the window and silently disable the replay button.
     */
    val lastIncomingWithAudio: StateFlow<Message?> =
        messageRepository.observeLatestIncomingVoice()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun playLastOriginal() {
        val msg = lastIncomingWithAudio.value ?: return
        viewModelScope.launch { playReplyUseCase.playManually(msg) }
    }

    /**
     * Tap-to-play, tap-again-to-stop. Called from the always-visible
     * HeroHeader button. If ANY playback is currently in flight
     * (original, summary TTS, queued reply), stop everything — the
     * button mirrors whatever the user is hearing. If nothing is
     * playing, start the most-recent incoming voice on the active
     * target. No-op when there's no audio to play AND nothing to
     * stop (button is disabled in that combined state).
     */
    fun toggleLastOriginal() {
        val st = playbackStatus.value
        val isAnyPlaying = st is PlayReplyUseCase.PlaybackStatus.Playing ||
            st is PlayReplyUseCase.PlaybackStatus.Paused
        if (isAnyPlaying) {
            playReplyUseCase.stopAll()
            return
        }
        val msg = lastIncomingWithAudio.value ?: return
        viewModelScope.launch {
            playReplyUseCase.playManually(msg)
        }
    }

    /**
     * v1.20.11 — count of messages parked in PlayReplyUseCase's pending
     * queue (silent-mode / autoPlay-off arrivals + in-call deferrals).
     * Drives the HeroHeader "▶ N pending" affordance: visible only when
     * >0; tap calls [drainPending] which kicks the audio chain to play
     * everything sequentially.
     *
     * Backed by playbackStatus (the existing Flow), which emits
     * PendingCount on every enqueue / drain transition. The map step
     * folds non-PendingCount statuses (Idle/Playing/Paused) into the
     * authoritative [pendingCount] snapshot so the count stays correct
     * while a queued message is actively playing.
     */
    val pendingPlaybackCount: StateFlow<Int> =
        playReplyUseCase.pendingCountFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /**
     * v1.20.11 — kick the pending-playback chain. No-op when the queue
     * is empty. Bypasses `silentMode` / `autoPlay` gates internally
     * because the user explicitly requested playback.
     */
    fun drainPending() {
        viewModelScope.launch {
            playReplyUseCase.playAllPending()
        }
    }

    val pinned: StateFlow<List<Conversation>> =
        conversationRepository.observePinned()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingReplyCounts: StateFlow<Map<String, Int>> =
        messageRepository.observePendingReplyCounts()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val outboxCount: StateFlow<Int> =
        messageRepository.observeOutboxCount()
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val quickReplies: StateFlow<List<QuickReply>> =
        quickReplyRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val quickReplyFilter: StateFlow<QuickReplyFilter> =
        settingsRepository.observe()
            .map { it.quickReplyFilter }
            .stateIn(viewModelScope, SharingStarted.Eagerly, QuickReplyFilter.ALL)

    fun setQuickReplyFilter(filter: QuickReplyFilter) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            if (current.quickReplyFilter != filter) {
                settingsRepository.update(current.copy(quickReplyFilter = filter))
            }
        }
    }

    // Terminal-state auto-dismiss Job. Cancelled + restarted when the
    // status changes so a rapid Sent→Error transition doesn't keep the
    // old timer alive.
    private var statusDismissJob: kotlinx.coroutines.Job? = null

    init {
        // Auto-clear the selected folder when it disappears (the user deleted
        // it from Manage). Without this the tab filter would keep returning
        // an empty list until the user manually taps "All".
        viewModelScope.launch {
            folders.collect { list ->
                val current = _selectedFolderId.value
                if (current != null && list.none { it.id == current }) {
                    _selectedFolderId.value = null
                }
            }
        }

        // Auto-dismiss the HeroHeader status pill after the user has had
        // time to read it (5 s). Terminal states only — Recording /
        // Sending / Validating must stay visible because they represent
        // ongoing work. Idle / PreviewReady / PreviewPlaying also stay
        // since they're either the default or a user-driven state.
        viewModelScope.launch {
            sendMessageUseCase.status.collect { st ->
                statusDismissJob?.cancel()
                val shouldDismiss = st is SendVoiceMtprotoUseCase.SendStatus.Sent ||
                    st is SendVoiceMtprotoUseCase.SendStatus.Error ||
                    st is SendVoiceMtprotoUseCase.SendStatus.Discarded ||
                    st is SendVoiceMtprotoUseCase.SendStatus.QueuedOffline
                if (shouldDismiss) {
                    statusDismissJob = viewModelScope.launch {
                        kotlinx.coroutines.delay(5_000)
                        // Re-check: user may have started a new action
                        // inside the delay window, which would have moved
                        // status off the terminal state — in that case
                        // don't clobber it.
                        if (sendMessageUseCase.status.value === st) {
                            sendMessageUseCase.reset()
                        }
                    }
                }
            }
        }

        // Watch incoming messages for the primary conversation and compute
        // chip suggestions. This must follow ConversationRepository's active
        // row, not SendVoiceMtprotoUseCase.activeTargetId: that one is only
        // non-null while the user is recording/sending, so suggestions would
        // otherwise disappear during normal idle use.
        viewModelScope.launch {
            combine(
                messageRepository.observeAllAcrossConversations(),
                conversationRepository.observeActive()
            ) { messages, active ->
                val activeId = active?.id
                if (activeId == null) return@combine null
                messages.asSequence()
                    .filter { it.conversationId == activeId }
                    .filter { it.direction == MessageDirection.INCOMING }
                    .sortedByDescending { it.createdAt }
                    .firstOrNull { !it.textContent.isNullOrBlank() || !it.transcription.isNullOrBlank() }
            }.collect { latest ->
                if (latest == null) { _suggestedChips.value = emptyList(); return@collect }
                if (latest.id == lastSuggestionSourceId) return@collect
                val ageMs = System.currentTimeMillis() - latest.createdAt
                if (ageMs > SUGGESTION_TTL_MS) return@collect
                val source = latest.textContent?.takeIf { it.isNotBlank() }
                    ?: latest.transcription.orEmpty()
                lastSuggestionSourceId = latest.id
                _suggestedChips.value = suggestQuickReplyUseCase.suggestFor(source)
            }
        }
    }

    fun startRecording(targetId: String?) {
        viewModelScope.launch {
            sendMessageUseCase.startRecording(targetId)
        }
    }

    fun stopAndSend() {
        viewModelScope.launch {
            sendMessageUseCase.stopAndSend()
        }
    }

    fun cancelRecording() {
        viewModelScope.launch {
            sendMessageUseCase.cancelRecording()
        }
    }

    fun previewPlay() {
        viewModelScope.launch { sendMessageUseCase.previewPlay() }
    }

    fun confirmPreview() {
        viewModelScope.launch { sendMessageUseCase.confirmPreview() }
    }

    fun cancelPreview() {
        viewModelScope.launch { sendMessageUseCase.cancelPreview() }
    }

    fun sendQuickReply(text: String, targetId: String? = null) {
        viewModelScope.launch {
            sendMessageUseCase.sendQuickReply(text, targetId)
            // Once the user sends anything, the current suggestions are stale.
            _suggestedChips.value = emptyList()
        }
    }

    fun sendQuickReplyShortcut(shortcut: QuickReply, targetId: String? = null) {
        viewModelScope.launch {
            when (shortcut.kind) {
                QuickReplyKind.TEXT -> {
                    sendMessageUseCase.sendQuickReply(shortcut.textContent, targetId)
                }
                QuickReplyKind.VOICE -> {
                    val path = shortcut.audioFilePath
                    if (!path.isNullOrBlank()) {
                        sendMessageUseCase.sendVoiceShortcut(
                            audioFilePath = path,
                            audioDurationMs = shortcut.audioDurationMs,
                            targetConversationId = targetId
                        )
                    }
                }
            }
            _suggestedChips.value = emptyList()
        }
    }

    fun dismissSuggestions() {
        _suggestedChips.value = emptyList()
    }

    fun acknowledgeStatus() {
        sendMessageUseCase.reset()
    }

    companion object {
        // Suggestions older than this are not worth showing — the user has
        // moved on from that incoming message. 5 min mirrors typical chat
        // reply windows.
        private const val SUGGESTION_TTL_MS = 5 * 60 * 1000L
    }

}
