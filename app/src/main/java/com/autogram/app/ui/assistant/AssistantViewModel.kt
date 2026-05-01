package com.autogram.app.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.usecase.SendVoiceMtprotoUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * v1.22.0 / issue #63 Phase 2 — drives the assistant turn:
 *   Idle → Listening → Sending → WaitingForReply → Replied / Failed.
 *
 * Reuses the existing [SendVoiceMtprotoUseCase] for record+send so the
 * assistant rides the same MTProto pipeline as Autogram's primary
 * voice flow. The "reply" is the next incoming message on the same
 * conversation: we observe [MessageRepository.observeAll] and watch
 * for an INCOMING message with createdAt > the moment the user
 * stopped recording.
 *
 * If the user backs out of [AssistantActivity] mid-turn the VM
 * disappears and this watcher dies — but the reply still lands via
 * [com.autogram.app.service.MtprotoReceiverService] and plays through
 * the existing autoplay pipeline. So the assistant UX gracefully
 * degrades into Autogram's normal incoming-message behavior.
 */
@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val sendVoiceMtprotoUseCase: SendVoiceMtprotoUseCase,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository
) : ViewModel() {

    sealed class State {
        object Idle : State()
        data class Listening(val targetTitle: String) : State()
        data class Sending(val targetTitle: String) : State()
        data class WaitingForReply(val targetTitle: String) : State()
        object Replied : State()
        data class Failed(val reason: String) : State()
        object NoTarget : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var replyWatcher: Job? = null
    private var timeoutJob: Job? = null

    /**
     * Resolve the user's active target, start recording, transition to
     * Listening. NoTarget if Settings hasn't been configured yet.
     */
    fun startListening() {
        viewModelScope.launch {
            val target = conversationRepository.getActive()
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
     * Stop recording, send to the active target, wait up to 60s for a
     * reply. The "stop tap moment" is the watermark for the reply
     * watcher — any incoming message in the same conversation after
     * that timestamp is treated as Merlin's reply.
     */
    fun stopAndSend() {
        val current = _state.value as? State.Listening ?: return
        val stoppedAt = System.currentTimeMillis()
        _state.value = State.Sending(current.targetTitle)
        viewModelScope.launch {
            val target = conversationRepository.getActive()
            if (target == null) {
                _state.value = State.Failed("Target disappeared during send")
                return@launch
            }
            val result = sendVoiceMtprotoUseCase.stopAndSend(skipPreview = true)
            if (result.isFailure) {
                _state.value = State.Failed(
                    result.exceptionOrNull()?.message ?: "Send failed"
                )
                return@launch
            }
            _state.value = State.WaitingForReply(current.targetTitle)
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
            _state.value = State.Replied
            timeoutJob?.cancel()
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
