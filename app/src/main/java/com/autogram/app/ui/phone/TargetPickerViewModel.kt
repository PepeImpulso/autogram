package com.autogram.app.ui.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.infrastructure.telegram.mtproto.ChatSummary
import com.autogram.app.infrastructure.telegram.mtproto.TdLibChatDirectory
import com.autogram.app.infrastructure.telegram.mtproto.TopicSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class TargetPickerViewModel @Inject constructor(
    private val chatDirectory: TdLibChatDirectory,
    private val conversationRepository: ConversationRepository
) : ViewModel() {

    sealed class Step {
        object Chats : Step()
        data class Topics(val chat: ChatSummary) : Step()
    }

    data class UiState(
        val step: Step = Step.Chats,
        val loading: Boolean = false,
        val chats: List<ChatSummary> = emptyList(),
        val topics: List<TopicSummary> = emptyList(),
        val error: String? = null,
        val saved: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        loadChats()
    }

    fun loadChats() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val chats = chatDirectory.listTargetChats()
                _state.value = _state.value.copy(
                    loading = false,
                    chats = chats,
                    step = Step.Chats
                )
            } catch (e: Exception) {
                Timber.e(e, "listTargetChats failed")
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Error cargando chats")
            }
        }
    }

    fun onChatSelected(chat: ChatSummary) {
        if (!chat.isForum) {
            // Non-forum chat — no topic step, persist immediately and finish.
            persistTarget(chat, topic = null)
            return
        }
        _state.value = _state.value.copy(
            step = Step.Topics(chat),
            loading = true,
            topics = emptyList(),
            error = null
        )
        viewModelScope.launch {
            try {
                val topics = chatDirectory.listForumTopics(chat.chatId)
                _state.value = _state.value.copy(loading = false, topics = topics)
            } catch (e: Exception) {
                Timber.e(e, "listForumTopics failed")
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Error cargando topics")
            }
        }
    }

    fun onTopicSelected(topic: TopicSummary) {
        val chat = (_state.value.step as? Step.Topics)?.chat ?: return
        persistTarget(chat, topic)
    }

    fun onBackToChats() {
        _state.value = _state.value.copy(step = Step.Chats, topics = emptyList(), error = null)
    }

    private fun persistTarget(chat: ChatSummary, topic: TopicSummary?) {
        viewModelScope.launch {
            try {
                // Reuse an existing pinned row if the same chat+topic is already
                // present — just flip the active flag onto it. Otherwise create a
                // fresh primary row and leave previously-pinned rows intact
                // (they'll survive as non-primary circles on the main screen).
                val topicId = topic?.forumTopicId?.toLong()
                val match = conversationRepository.getPinned().firstOrNull {
                    it.targetChatId == chat.chatId.toString() && it.topicId == topicId
                }
                val id = if (match != null) match.id else {
                    val newId = UUID.randomUUID().toString()
                    conversationRepository.upsert(
                        Conversation(
                            id = newId,
                            targetChatId = chat.chatId.toString(),
                            targetTitle = chat.title,
                            botToken = "",
                            isActive = true,
                            createdAt = System.currentTimeMillis(),
                            topicId = topicId,
                            topicName = topic?.name,
                            isPinned = true
                        )
                    )
                    newId
                }
                conversationRepository.setActive(id)
                _state.value = _state.value.copy(saved = true)
            } catch (e: Exception) {
                Timber.e(e, "persistTarget failed")
                _state.value = _state.value.copy(error = e.message ?: "Error guardando destino")
            }
        }
    }
}
