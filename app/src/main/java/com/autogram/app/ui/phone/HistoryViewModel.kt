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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val playReplyUseCase: PlayReplyUseCase
) : AndroidViewModel(application) {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _conversation = MutableStateFlow<Conversation?>(null)
    /** Conversation whose messages we're showing — for the toolbar title. */
    val conversation: StateFlow<Conversation?> = _conversation.asStateFlow()

    val playbackStatus: StateFlow<PlayReplyUseCase.PlaybackStatus> = playReplyUseCase.playbackStatus

    init {
        viewModelScope.launch {
            // v1.20.14: HistoryActivity can be opened with a specific
            // conversation ID (e.g. user tapped a per-circle pending badge
            // on the Home screen). Fall back to the active conversation
            // when no ID is provided so the bottom-nav entry-point keeps
            // working as before.
            val explicitId: String? = savedStateHandle[HistoryActivity.EXTRA_CONVERSATION_ID]
            val target = if (!explicitId.isNullOrBlank()) {
                conversationRepository.getById(explicitId)
            } else {
                conversationRepository.getActive()
            }
            _conversation.value = target
            if (target != null) {
                // v1.20.15 CRITICAL FIX (was v1.20.14 data-loss bug):
                // Bump conversations.lastRepliedAt via the targeted UPDATE
                // helper, NOT via upsert. ConversationDao.upsert is
                // @Insert(onConflict = REPLACE), which SQLite implements
                // as DELETE-then-INSERT on the conflicting row — and
                // MessageEntity has ForeignKey(onDelete = CASCADE) on
                // conversations.id, so the upsert was wiping out every
                // message for the tapped conversation. setLastRepliedAt
                // is a plain UPDATE with no FK side-effects.
                //
                // Acknowledge-on-view: clears the per-circle pending
                // badge by making observePendingReplyCounts'
                // `m.createdAt > c.lastRepliedAt` filter return zero for
                // existing incoming messages.
                conversationRepository.setLastRepliedAt(
                    target.id, System.currentTimeMillis()
                )
                messageRepository.observeAll(target.id).collect { messages ->
                    _messages.value = messages
                }
            }
        }
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
}
