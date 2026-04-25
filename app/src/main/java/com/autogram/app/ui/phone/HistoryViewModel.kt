package com.autogram.app.ui.phone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val playReplyUseCase: PlayReplyUseCase
) : AndroidViewModel(application) {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    val playbackStatus: StateFlow<PlayReplyUseCase.PlaybackStatus> = playReplyUseCase.playbackStatus

    init {
        viewModelScope.launch {
            val conversation = conversationRepository.getActive()
            if (conversation != null) {
                messageRepository.observeAll(conversation.id).collect { messages ->
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
