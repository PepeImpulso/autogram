package com.autogram.app.ui.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.infrastructure.telegram.mtproto.ChatSummary
import com.autogram.app.infrastructure.telegram.mtproto.TdLibChatDirectory
import com.autogram.app.infrastructure.telegram.mtproto.TopicSummary
import com.autogram.app.infrastructure.telegram.mtproto.asInt32OrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
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
        // Currently ticked checkboxes.
        val selectedTopicIds: Set<Int> = emptySet(),
        // Set of topic ids that were already pinned when the Topics step opened;
        // used to compute the diff (add vs. remove) on confirm.
        val initiallySelectedTopicIds: Set<Int> = emptySet(),
        // Topic ids that are currently the primary (isActive) — protected from
        // removal so the user can't accidentally unpin their Merlin circle.
        val primaryTopicIds: Set<Int> = emptySet(),
        val error: String? = null,
        val saved: Boolean = false
    ) {
        val hasChanges: Boolean get() = selectedTopicIds != initiallySelectedTopicIds
        val addedCount: Int get() = (selectedTopicIds - initiallySelectedTopicIds).size
        val removedCount: Int get() = (initiallySelectedTopicIds - selectedTopicIds).size
    }

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
            initiallySelectedTopicIds = emptySet(),
            primaryTopicIds = emptySet(),
            error = null
        )
        viewModelScope.launch {
            try {
                val topics = chatDirectory.listForumTopics(chat.chatId)
                val alreadyPinned = conversationRepository.getPinned()
                    .filter { it.targetChatId == chat.chatId.toString() && it.topicId != null }
                val alreadyPinnedIds = alreadyPinned
                    .mapNotNull { it.topicId.asInt32OrNull() }
                    .toSet()
                val primaryIds = alreadyPinned.filter { it.isActive }
                    .mapNotNull { it.topicId.asInt32OrNull() }
                    .toSet()
                _state.value = _state.value.copy(
                    loading = false,
                    topics = topics,
                    selectedTopicIds = alreadyPinnedIds,
                    initiallySelectedTopicIds = alreadyPinnedIds,
                    primaryTopicIds = primaryIds
                )
            } catch (e: Exception) {
                Timber.e(e, "listForumTopics failed")
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Error cargando topics")
            }
        }
    }

    fun toggleTopic(topic: TopicSummary) {
        // Can't unpin the primary from here — guide the user to set a different
        // primary first in "Accesos rápidos".
        if (topic.forumTopicId in _state.value.primaryTopicIds) return
        val current = _state.value.selectedTopicIds
        val next = if (topic.forumTopicId in current) current - topic.forumTopicId
                   else current + topic.forumTopicId
        _state.value = _state.value.copy(selectedTopicIds = next)
    }

    fun confirmSelectedTopics() {
        val chat = (_state.value.step as? Step.Topics)?.chat ?: return
        val initial = _state.value.initiallySelectedTopicIds
        val selected = _state.value.selectedTopicIds
        val toAddIds = selected - initial
        val toRemoveIds = (initial - selected) - _state.value.primaryTopicIds
        val toAdd = _state.value.topics.filter { it.forumTopicId in toAddIds }

        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val existing = conversationRepository.getPinned()
                val maxOrder = existing.maxOfOrNull { it.sortOrder } ?: 0
                toAdd.forEachIndexed { idx, topic ->
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
                // Remove: find the row by (chatId, topicId) and delete it if
                // it's just a pinned row; if it's the primary, skip.
                for (topicId in toRemoveIds) {
                    val row = existing.firstOrNull {
                        it.targetChatId == chat.chatId.toString() &&
                            it.topicId == topicId.toLong()
                    } ?: continue
                    if (row.isActive) continue
                    // Drop any photo file the user attached to this topic so
                    // unpinning it doesn't orphan a file in
                    // filesDir/circle_photos/. ManagePinnedActivity.remove()
                    // already does this; without the symmetric cleanup here
                    // the retention worker's sweep wouldn't catch it (that
                    // sweep only covers audio dirs, not circle_photos).
                    row.photoLocalPath?.let { runCatching { File(it).delete() } }
                    conversationRepository.delete(row.id)
                }
                _state.value = _state.value.copy(saved = true)
            } catch (e: Exception) {
                Timber.e(e, "sync pinned topics failed")
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
            initiallySelectedTopicIds = emptySet(),
            primaryTopicIds = emptySet(),
            error = null
        )
    }
}
