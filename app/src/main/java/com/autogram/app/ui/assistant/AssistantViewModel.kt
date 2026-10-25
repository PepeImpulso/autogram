package com.autogram.app.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.domain.usecase.SendVoiceMtprotoUseCase
import com.autogram.app.infrastructure.transcription.TranscriptionEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * v1.22.0 / issue #63 Phase 2 — drives the assistant turn:
 *   Idle → Listening → Sending → WaitingForReply → Replied / Failed.
 *
 * v1.23.0-rc.2 / Phase 5 starter — added [selectedTargetId] so the
 * assistant can route the voice note to any pinned contact (default
 * still the configured primary target).
 *
 * v1.23.0-rc.3 / Phase 5 voice commands — after the user stops
 * recording, the audio is transcribed locally and parsed for action
 * keywords (play pending / repeat last / pause / resume / stop). When
 * a command matches, the audio is discarded and the action runs;
 * otherwise the recording is sent as a normal voice note. The state
 * machine grew two intermediate states: [State.Transcribing] (visible
 * while the on-device engine works) and [State.ActionExecuted] (brief
 * confirmation after a command runs, then the activity closes).
 */
@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val sendVoiceMtprotoUseCase: SendVoiceMtprotoUseCase,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val playReplyUseCase: PlayReplyUseCase,
    private val transcriptionEngine: TranscriptionEngine,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    sealed class State {
        object Idle : State()
        data class Listening(val targetTitle: String) : State()
        /** Audio recorded; running on-device transcription + command parsing. */
        object Transcribing : State()
        data class Sending(val targetTitle: String) : State()
        data class WaitingForReply(val targetTitle: String) : State()
        object Replied : State()
        /** A voice command matched and was dispatched. Brief confirmation before close. */
        data class ActionExecuted(val command: AssistantCommandParser.Command) : State()
        data class Failed(val reason: String) : State()
        object NoTarget : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** All pinned conversations — drives the contact picker chip row. */
    val availableContacts: StateFlow<List<Conversation>> =
        conversationRepository.observePinned()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Currently selected target for the assistant turn. */
    private val _selectedTargetId = MutableStateFlow<String?>(null)
    val selectedTargetId: StateFlow<String?> = _selectedTargetId.asStateFlow()

    private var replyWatcher: Job? = null
    private var timeoutJob: Job? = null

    init {
        viewModelScope.launch {
            _selectedTargetId.value = conversationRepository.getActive()?.id
        }
    }

    /** User tapped a contact chip — switch the target for the next turn. */
    fun setTargetContact(id: String?) {
        _selectedTargetId.value = id
    }

    /**
     * Resolve the selected target, start recording, transition to
     * Listening. NoTarget if Settings hasn't been configured yet.
     */
    fun startListening() {
        viewModelScope.launch {
            val target = resolveTarget()
            if (target == null) {
                _state.value = State.NoTarget
                return@launch
            }
            val title = resolveTitle(target.topicName, target.targetTitle)
            val result = sendVoiceMtprotoUseCase.startRecording(targetConversationId = target.id)
            if (result.isFailure) {
                _state.value = State.Failed(
                    result.exceptionOrNull()?.message ?: "Recording failed"
                )
                return@launch
            }
            _state.value = State.Listening(title)
        }
    }

    /**
     * Stop recording, transcribe locally, parse for commands. If a
     * command matches → execute, drop the audio, brief confirmation,
     * close. Otherwise fall through to the normal voice-send path
     * with the same reply-watcher behavior as Phase 2.
     */
    fun stopAndProcess() {
        val current = _state.value as? State.Listening ?: return
        val stoppedAt = System.currentTimeMillis()
        val targetTitle = current.targetTitle
        _state.value = State.Transcribing
        viewModelScope.launch {
            val target = resolveTarget()
            if (target == null) {
                _state.value = State.Failed("Target disappeared during send")
                return@launch
            }
            val audioFile = sendVoiceMtprotoUseCase.stopWithoutSending()
            if (audioFile == null || !audioFile.exists()) {
                _state.value = State.Failed("Couldn't save the recording")
                return@launch
            }
            val locale = settingsLocale()
            val transcript = runCatching {
                transcriptionEngine.transcribe(audioFile, locale)
            }.getOrNull()
            val text = (transcript as? TranscriptionEngine.Result.Success)?.text
            val command = AssistantCommandParser.parse(text)

            if (command != null) {
                Timber.i("assistant voice command matched: $command (transcriptLength=${text?.length ?: 0})")
                runCatching { audioFile.delete() }
                executeCommand(command)
                _state.value = State.ActionExecuted(command)
                return@launch
            }

            // No command matched — send the recording as a normal voice note.
            val durationMs = audioFile.length().let { bytes ->
                // Best-effort estimation — TDLib will refine the duration server-side.
                // OPUS bitrate ~24kbps = 3KB/s; floor at 0.
                ((bytes * 1000L) / 3000L).coerceAtLeast(0L)
            }
            _state.value = State.Sending(targetTitle)
            val send = sendVoiceMtprotoUseCase.sendVoiceShortcut(
                audioFilePath = audioFile.absolutePath,
                audioDurationMs = durationMs,
                targetConversationId = target.id
            )
            if (send.isFailure) {
                _state.value = State.Failed(
                    send.exceptionOrNull()?.message ?: "Send failed"
                )
                return@launch
            }
            _state.value = State.WaitingForReply(targetTitle)
            startReplyWatcher(target.id, stoppedAt)
        }
    }

    /**
     * Discard the current recording. Resets to Idle so the user can
     * either retry or close the screen.
     */
    fun cancel() {
        replyWatcher?.cancel()
        timeoutJob?.cancel()
        viewModelScope.launch {
            sendVoiceMtprotoUseCase.cancelRecording()
            _state.value = State.Idle
        }
    }

    /**
     * Reset to Idle without canceling anything in flight. Used by the
     * "try again" affordance on Failed.
     */
    fun reset() {
        replyWatcher?.cancel()
        timeoutJob?.cancel()
        _state.value = State.Idle
    }

    /**
     * Resolve the active conversation honoring [_selectedTargetId]. When
     * the user hasn't picked anything yet (or the picked id is stale),
     * fall back to whatever Settings declares as the primary target.
     */
    private suspend fun resolveTarget(): Conversation? {
        val id = _selectedTargetId.value
        if (id != null) {
            val byId = conversationRepository.getById(id)
            if (byId != null) return byId
        }
        return conversationRepository.getActive()
    }

    /**
     * Locale to feed the transcription engine. Honors the user's
     * configured language tag from Settings; falls back to device
     * default. Same resolution PlayReplyUseCase uses.
     */
    private suspend fun settingsLocale(): Locale {
        val tag = settingsRepository.get().languageTag
        return if (tag.isNotBlank()) Locale.forLanguageTag(tag) else Locale.getDefault()
    }

    private fun executeCommand(command: AssistantCommandParser.Command) {
        viewModelScope.launch {
            when (command) {
                AssistantCommandParser.Command.PlayPending -> playReplyUseCase.playAllPending()
                AssistantCommandParser.Command.RepeatLast -> {
                    val last = lastPlayableMessage() ?: return@launch
                    playReplyUseCase.playManually(last)
                }
                AssistantCommandParser.Command.Pause -> playReplyUseCase.pause()
                AssistantCommandParser.Command.Resume -> playReplyUseCase.resume()
                AssistantCommandParser.Command.Stop -> playReplyUseCase.stopAll()
            }
        }
    }

    /** Most-recent incoming voice message, for "repeat last" — null when there's nothing to replay. */
    private suspend fun lastPlayableMessage() =
        messageRepository.getAll()
            .filter { it.direction == MessageDirection.INCOMING && !it.audioFilePath.isNullOrBlank() }
            .maxByOrNull { it.createdAt }

    private fun startReplyWatcher(conversationId: String, since: Long) {
        replyWatcher?.cancel()
        timeoutJob?.cancel()

        replyWatcher = viewModelScope.launch {
            messageRepository.observeAll(conversationId)
                .map { msgs ->
                    msgs.firstOrNull {
                        it.direction == MessageDirection.INCOMING && it.createdAt > since
                    }
                }
                .filterNotNull()
                .first()
            // Guard against the timeoutJob having flipped state to Failed
            // while we were between .first() returning and the setter
            // below — both jobs run on the same Main dispatcher, but the
            // continuation after .first() has no suspension point before
            // the setter, so a cancel() from the timeout side wouldn't
            // fire in time.
            if (_state.value is State.WaitingForReply) {
                _state.value = State.Replied
                timeoutJob?.cancel()
            }
        }
        timeoutJob = viewModelScope.launch {
            delay(REPLY_TIMEOUT_MS)
            if (_state.value is State.WaitingForReply) {
                replyWatcher?.cancel()
                _state.value = State.Failed("No reply within ${REPLY_TIMEOUT_MS / 1000}s")
            }
        }
    }

    private fun resolveTitle(topicName: String?, targetTitle: String?): String {
        return topicName?.takeIf { it.isNotBlank() }
            ?: targetTitle?.takeIf { it.isNotBlank() }
            ?: "Merlin"
    }

    override fun onCleared() {
        super.onCleared()
        replyWatcher?.cancel()
        timeoutJob?.cancel()
    }

    companion object {
        private const val REPLY_TIMEOUT_MS = 60_000L
    }
}
