package com.autogram.app.ui.phone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.QuickReplyRepository
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.domain.usecase.SendVoiceMtprotoUseCase
import com.autogram.app.domain.usecase.SuggestQuickReplyUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val sendMessageUseCase: SendVoiceMtprotoUseCase,
    private val playReplyUseCase: PlayReplyUseCase,
    private val quickReplyRepository: QuickReplyRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val suggestQuickReplyUseCase: SuggestQuickReplyUseCase
) : AndroidViewModel(application) {

    // Ephemeral suggestion chips. Recomputed whenever a new INCOMING message
    // (voice with transcription, or text) arrives on the active conversation.
    // Cleared when the user sends a chip (tap), sends a voice reply, or
    // after [SUGGESTION_TTL_MS] of inactivity.
    private val _suggestedChips = MutableStateFlow<List<SuggestQuickReplyUseCase.Suggestion>>(emptyList())
    val suggestedChips: StateFlow<List<SuggestQuickReplyUseCase.Suggestion>> = _suggestedChips.asStateFlow()
    private var lastSuggestionSourceId: String? = null

    val sendStatus: StateFlow<SendVoiceMtprotoUseCase.SendStatus> = sendMessageUseCase.status
    val playbackStatus: StateFlow<PlayReplyUseCase.PlaybackStatus> = playReplyUseCase.playbackStatus
    val activeTargetId: StateFlow<String?> = sendMessageUseCase.activeTargetId

    val pinned: StateFlow<List<Conversation>> =
        conversationRepository.observePinned()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingReplyCounts: StateFlow<Map<String, Int>> =
        messageRepository.observePendingReplyCounts()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val outboxCount: StateFlow<Int> =
        messageRepository.observeOutboxCount()
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val quickReplies: StateFlow<List<Pair<String, String>>> =
        quickReplyRepository.observeAll()
            .map { list -> list.map { it.label to it.textContent } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, defaultQuickReplies())

    init {
        // Watch incoming messages across the active conversation and compute
        // chip suggestions. We use observeAllAcrossConversations because the
        // active target may change while we're running; `activeTargetId`
        // gates which messages are relevant.
        viewModelScope.launch {
            combine(
                messageRepository.observeAllAcrossConversations(),
                activeTargetId
            ) { messages, activeId ->
                if (activeId == null) return@combine null
                messages.asSequence()
                    .filter { it.conversationId == activeId }
                    .filter { it.direction == MessageDirection.INCOMING }
                    .sortedByDescending { it.createdAt }
                    .firstOrNull { !it.textContent.isNullOrBlank() || !it.transcription.isNullOrBlank() }
            }.collect { latest ->
                if (latest == null) { _suggestedChips.value = emptyList(); return@collect }
                if (latest.id == lastSuggestionSourceId) return@collect
                val ageMs = System.currentTimeMillis() - latest.createdAt
                if (ageMs > SUGGESTION_TTL_MS) return@collect
                val source = latest.textContent?.takeIf { it.isNotBlank() }
                    ?: latest.transcription.orEmpty()
                lastSuggestionSourceId = latest.id
                _suggestedChips.value = suggestQuickReplyUseCase.suggestFor(source)
            }
        }
    }

    fun startRecording(targetId: String?) {
        viewModelScope.launch {
            sendMessageUseCase.startRecording(targetId)
        }
    }

    fun stopAndSend() {
        viewModelScope.launch {
            sendMessageUseCase.stopAndSend()
        }
    }

    fun cancelRecording() {
        viewModelScope.launch {
            sendMessageUseCase.cancelRecording()
        }
    }

    fun previewPlay() {
        viewModelScope.launch { sendMessageUseCase.previewPlay() }
    }

    fun confirmPreview() {
        viewModelScope.launch { sendMessageUseCase.confirmPreview() }
    }

    fun cancelPreview() {
        viewModelScope.launch { sendMessageUseCase.cancelPreview() }
    }

    fun sendQuickReply(text: String, targetId: String? = null) {
        viewModelScope.launch {
            sendMessageUseCase.sendQuickReply(text, targetId)
            // Once the user sends anything, the current suggestions are stale.
            _suggestedChips.value = emptyList()
        }
    }

    fun dismissSuggestions() {
        _suggestedChips.value = emptyList()
    }

    fun acknowledgeStatus() {
        sendMessageUseCase.reset()
    }

    companion object {
        // Suggestions older than this are not worth showing — the user has
        // moved on from that incoming message. 5 min mirrors typical chat
        // reply windows.
        private const val SUGGESTION_TTL_MS = 5 * 60 * 1000L
    }

    private fun defaultQuickReplies(): List<Pair<String, String>> {
        val ctx = getApplication<Application>()
        return listOf(
            ctx.getString(com.autogram.app.R.string.quick_reply_driving)
                to ctx.getString(com.autogram.app.R.string.quick_reply_driving_text),
            ctx.getString(com.autogram.app.R.string.quick_reply_later)
                to ctx.getString(com.autogram.app.R.string.quick_reply_later_text),
            ctx.getString(com.autogram.app.R.string.quick_reply_ok)
                to ctx.getString(com.autogram.app.R.string.quick_reply_ok_text)
        )
    }
}
