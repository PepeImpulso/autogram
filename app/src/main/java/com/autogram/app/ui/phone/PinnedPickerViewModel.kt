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
class PinnedPickerViewModel @Inject constructor(
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
        val selectedTopicIds: Set<Int> = emptySet(),
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
            // Non-forum chat — pin directly (single item) and finish.
            pinNonForumChat(chat)
            return
        }
        _state.value = _state.value.copy(
            step = Step.Topics(chat),
            loading = true,
            topics = emptyList(),
            selectedTopicIds = emptySet(),
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

    fun toggleTopic(topic: TopicSummary) {
        val current = _state.value.selectedTopicIds
        val next = if (topic.forumTopicId in current) current - topic.forumTopicId
                   else current + topic.forumTopicId
        _state.value = _state.value.copy(selectedTopicIds = next)
    }

    fun confirmSelectedTopics() {
        val chat = (_state.value.step as? Step.Topics)?.chat ?: return
        val selectedIds = _state.value.selectedTopicIds
        val chosen = _state.value.topics.filter { it.forumTopicId in selectedIds }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val existing = conversationRepository.getPinned()
                val maxOrder = existing.maxOfOrNull { it.sortOrder } ?: 0
                chosen.forEachIndexed { idx, topic ->
                    // Avoid duplicates: same chat + topic already pinned → skip.
                    val duplicate = existing.any {
                        it.targetChatId == chat.chatId.toString() &&
                            it.topicId == topic.forumTopicId.toLong()
                    }
                    if (duplicate) return@forEachIndexed
                    conversationRepository.upsert(
                        Conversation(
                            id = UUID.randomUUID().toString(),
                            targetChatId = chat.chatId.toString(),
                            targetTitle = chat.title,
                            botToken = "",
                            isActive = false,
                            createdAt = now + idx,
                            topicId = topic.forumTopicId.toLong(),
                            topicName = topic.name,
                            isPinned = true,
                            sortOrder = maxOrder + 1 + idx
                        )
                    )
                }
                _state.value = _state.value.copy(saved = true)
            } catch (e: Exception) {
                Timber.e(e, "pin topics failed")
                _state.value = _state.value.copy(error = e.message ?: "Error guardando topics")
            }
        }
    }

    private fun pinNonForumChat(chat: ChatSummary) {
        viewModelScope.launch {
            try {
                val existing = conversationRepository.getPinned()
                val duplicate = existing.any {
                    it.targetChatId == chat.chatId.toString() && it.topicId == null
                }
                if (!duplicate) {
                    val maxOrder = existing.maxOfOrNull { it.sortOrder } ?: 0
                    conversationRepository.upsert(
                        Conversation(
                            id = UUID.randomUUID().toString(),
                            targetChatId = chat.chatId.toString(),
                            targetTitle = chat.title,
                            botToken = "",
                            isActive = false,
                            createdAt = System.currentTimeMillis(),
                            topicId = null,
                            topicName = null,
                            isPinned = true,
                            sortOrder = maxOrder + 1
                        )
                    )
                }
                _state.value = _state.value.copy(saved = true)
            } catch (e: Exception) {
                Timber.e(e, "pin chat failed")
                _state.value = _state.value.copy(error = e.message ?: "Error guardando destino")
            }
        }
    }

    fun onBackToChats() {
        _state.value = _state.value.copy(
            step = Step.Chats,
            topics = emptyList(),
            selectedTopicIds = emptySet(),
            error = null
        )
    }
}
