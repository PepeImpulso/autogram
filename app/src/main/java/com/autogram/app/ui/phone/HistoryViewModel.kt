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

    fun onPlayClicked(message: Message) {
        viewModelScope.launch { playReplyUseCase.playManually(message) }
    }

    fun onPauseClicked() {
        playReplyUseCase.pause()
    }

    fun onResumeClicked() {
        playReplyUseCase.resume()
    }
}
