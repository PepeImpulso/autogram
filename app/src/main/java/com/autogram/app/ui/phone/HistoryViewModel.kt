package com.autogram.app.ui.phone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.usecase.PlayReplyUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val playReplyUseCase: PlayReplyUseCase
) : AndroidViewModel(application) {

    /**
     * v1.20.16 — the conversation we're currently viewing. Driven by:
     *  - the EXTRA_CONVERSATION_ID intent extra (e.g. user tapped a
     *    per-circle badge on Home),
     *  - the active conversation as a fallback for the bottom-nav
     *    entry point,
     *  - [selectConversation] when the user picks a different chat
     *    from the in-toolbar dropdown.
     *
     * Changes here drive both the messages list and the lastRepliedAt
     * "ack" bump (so the per-circle pending badge clears the moment the
     * user views the conversation, even via dropdown switch).
     */
    private val _currentConversationId = MutableStateFlow<String?>(null)

    /** All pinned conversations — populates the toolbar dropdown chooser. */
    val pinned: StateFlow<List<Conversation>> =
        conversationRepository.observePinned()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _conversation = MutableStateFlow<Conversation?>(null)
    /** Conversation whose messages we're showing — for the toolbar title. */
    val conversation: StateFlow<Conversation?> = _conversation.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    val playbackStatus: StateFlow<PlayReplyUseCase.PlaybackStatus> = playReplyUseCase.playbackStatus

    init {
        // Resolve the initial conversation: explicit intent extra wins,
        // active conversation is the fallback. Runs once at construction.
        viewModelScope.launch {
            val explicitId: String? = savedStateHandle[HistoryActivity.EXTRA_CONVERSATION_ID]
            val initialId = if (!explicitId.isNullOrBlank()) explicitId
                            else conversationRepository.getActive()?.id
            _currentConversationId.value = initialId
        }
        // Side-effects on conversation switch: bump lastRepliedAt as an
        // "I've seen these" ack so the per-circle pending badge clears,
        // and load the conversation entity for the toolbar title.
        // Plain UPDATE (NOT upsert) — see the v1.20.15 release notes for
        // the FK-CASCADE catastrophe that called for this lesson.
        viewModelScope.launch {
            _currentConversationId
                .filterNotNull()
                .distinctUntilChanged()
                .collect { id ->
                    conversationRepository.setLastRepliedAt(
                        id, System.currentTimeMillis()
                    )
                    _conversation.value = conversationRepository.getById(id)
                }
        }
        // Switch the messages stream when the user picks a different
        // conversation. flatMapLatest cancels the previous observeAll
        // subscription cleanly so we don't double-emit.
        viewModelScope.launch {
            _currentConversationId
                .filterNotNull()
                .distinctUntilChanged()
                .flatMapLatest { id -> messageRepository.observeAll(id) }
                .collect { _messages.value = it }
        }
    }

    /**
     * v1.20.16 — change the conversation being viewed. Fired from the
     * toolbar dropdown chooser. No-op if the user picks the same
     * conversation already on screen.
     */
    fun selectConversation(id: String) {
        _currentConversationId.value = id
    }

    /**
     * v1.20.11 — when the tapped row is a queued pending message, route
     * through [PlayReplyUseCase.playFromHere] so playback starts at this
     * message and chains through the rest of the queue. Non-pending rows
     * (already played, sending, etc.) keep the old standalone-replay
     * semantic — `playManually` ignores the queue entirely.
     */
    fun onPlayClicked(message: Message) {
        viewModelScope.launch {
            if (message.state == com.autogram.app.domain.model.MessageState.PENDING_PLAYBACK) {
                playReplyUseCase.playFromHere(message)
            } else {
                playReplyUseCase.playManually(message)
            }
        }
    }

    fun onPauseClicked() {
        playReplyUseCase.pause()
    }

    fun onResumeClicked() {
        playReplyUseCase.resume()
    }

    /**
     * v1.20.17 — wipes every message in the currently-viewed conversation
     * and the .ogg files on disk that backed them. Stops in-flight
     * playback first and reconciles the pending-playback queue so a
     * post-clear playNext doesn't try to play a row that's already gone.
     * No-op if no conversation is loaded.
     */
    fun clearHistory() {
        val id = _currentConversationId.value ?: return
        viewModelScope.launch {
            playReplyUseCase.clearForConversation(id)
            messageRepository.clearForConversation(id)
        }
    }
}
