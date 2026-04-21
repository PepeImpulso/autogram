package com.autogram.app.ui.phone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.QuickReplyRepository
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.domain.usecase.SendVoiceMtprotoUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    private val messageRepository: MessageRepository
) : AndroidViewModel(application) {

    val sendStatus: StateFlow<SendVoiceMtprotoUseCase.SendStatus> = sendMessageUseCase.status
    val playbackStatus: StateFlow<PlayReplyUseCase.PlaybackStatus> = playReplyUseCase.playbackStatus
    val activeTargetId: StateFlow<String?> = sendMessageUseCase.activeTargetId

    val pinned: StateFlow<List<Conversation>> =
        conversationRepository.observePinned()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingReplyCounts: StateFlow<Map<String, Int>> =
        messageRepository.observePendingReplyCounts()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val quickReplies: StateFlow<List<Pair<String, String>>> =
        quickReplyRepository.observeAll()
            .map { list -> list.map { it.label to it.textContent } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, defaultQuickReplies())

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

    fun sendQuickReply(text: String, targetId: String? = null) {
        viewModelScope.launch {
            sendMessageUseCase.sendQuickReply(text, targetId)
        }
    }

    fun acknowledgeStatus() {
        sendMessageUseCase.reset()
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
