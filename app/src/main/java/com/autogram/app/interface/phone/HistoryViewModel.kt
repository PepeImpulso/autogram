package com.autogram.app.interface.phone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    application: Application,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository
) : AndroidViewModel(application) {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

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
}
