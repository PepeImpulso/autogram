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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * v1.22.0 — History is now a cross-conversation log of every message
 * (voice + text, incoming + outgoing). Filtering is done via the
 * [contactFilter] StateFlow, set by tapping a chip in the contact row;
 * `null` means "all contacts".
 *
 * The old per-conversation scope (driven by the dropdown chooser at
 * the top of the toolbar) is gone. The home-screen per-circle badge
 * still passes [HistoryActivity.EXTRA_CONVERSATION_ID] — we honor it
 * by setting [contactFilter] to that id on first load so the user
 * lands pre-filtered to that circle.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val playReplyUseCase: PlayReplyUseCase
) : AndroidViewModel(application) {

    /** Selected contact filter — `null` means "all contacts". */
    private val _contactFilter = MutableStateFlow<String?>(null)
    val contactFilter: StateFlow<String?> = _contactFilter.asStateFlow()

    /** All pinned conversations — populates the contact filter chip row. */
    val pinned: StateFlow<List<Conversation>> =
        conversationRepository.observePinned()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Cross-conversation message log, filtered by [contactFilter] and sorted
     * newest-first. Combines [MessageRepository.observeAllAcrossConversations]
     * with the live filter selection.
     */
    val messages: StateFlow<List<Message>> = combine(
        messageRepository.observeAllAcrossConversations(),
        _contactFilter
    ) { all, filterId ->
        val scoped = if (filterId == null) all else all.filter { it.conversationId == filterId }
        scoped.sortedByDescending { it.createdAt }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val playbackStatus: StateFlow<PlayReplyUseCase.PlaybackStatus> = playReplyUseCase.playbackStatus

    init {
        // Honor the EXTRA_CONVERSATION_ID intent extra — when the user taps
        // a per-circle pending badge on Home, land pre-filtered to that
        // circle. Empty extra → leave contactFilter null (= all).
        val explicitId: String? = savedStateHandle[HistoryActivity.EXTRA_CONVERSATION_ID]
        if (!explicitId.isNullOrBlank()) {
            _contactFilter.value = explicitId
            // Bump the conversation's lastRepliedAt as an "I've seen
            // these" ack so the per-circle pending badge clears
            // immediately. Same semantics as v1.20.16; only the trigger
            // moved from a dropdown selection to the intent-extra path.
            viewModelScope.launch {
                conversationRepository.setLastRepliedAt(explicitId, System.currentTimeMillis())
            }
        }
    }

    /** Set or clear the contact filter — null means all contacts. */
    fun setContactFilter(conversationId: String?) {
        _contactFilter.value = conversationId
        // When the user picks a specific contact, also bump its
        // lastRepliedAt so per-circle badges clear on view.
        if (conversationId != null) {
            viewModelScope.launch {
                conversationRepository.setLastRepliedAt(conversationId, System.currentTimeMillis())
            }
        }
    }

    /**
     * v1.20.11 — when the tapped row is a queued pending message, route
     * through [PlayReplyUseCase.playFromHere] so playback starts at this
     * message and chains through the rest of the queue. Non-pending rows
     * (already played, sending, etc.) keep the standalone-replay
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
     * v1.23.0-rc.6 — passthroughs for the live `0:15 / 1:30` readout in
     * [MessageRow]. Both are polled at ~250 ms by the row when it's the
     * active playback target. Returning null suppresses the readout for
     * that frame (e.g. TTS playback or MediaPlayer not yet loaded).
     */
    fun currentPositionMs(): Int? = playReplyUseCase.currentPositionMs()
    fun durationMs(): Int? = playReplyUseCase.durationMs()
    fun seekTo(positionMs: Int) = playReplyUseCase.seekTo(positionMs)

    /**
     * Wipe every message in the currently-filtered conversation. Disabled
     * when the filter is null (no all-conversations clear — too
     * destructive). Stops in-flight playback for the same conversation
     * before deleting.
     */
    fun clearHistory() {
        val id = _contactFilter.value ?: return
        viewModelScope.launch {
            playReplyUseCase.clearForConversation(id)
            messageRepository.clearForConversation(id)
        }
    }
}
