package com.autogram.app.ui.phone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.domain.repository.QuickReplyRepository
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.domain.usecase.SendMessageUseCase
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
    private val sendMessageUseCase: SendMessageUseCase,
    private val playReplyUseCase: PlayReplyUseCase,
    private val quickReplyRepository: QuickReplyRepository
) : AndroidViewModel(application) {

    val sendStatus: StateFlow<SendMessageUseCase.SendStatus> = sendMessageUseCase.currentStatus
    val playbackStatus: StateFlow<PlayReplyUseCase.PlaybackStatus> = playReplyUseCase.playbackStatus

    val quickReplies: StateFlow<List<Pair<String, String>>> =
        quickReplyRepository.observeAll()
            .map { list -> list.map { it.label to it.textContent } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, defaultQuickReplies())

    fun startRecording() {
        viewModelScope.launch {
            sendMessageUseCase.startRecording(getApplication())
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

    fun sendQuickReply(text: String) {
        viewModelScope.launch {
            sendMessageUseCase.sendQuickReply(text)
        }
    }

    fun acknowledgeStatus() {
        sendMessageUseCase.reset()
    }

    private fun defaultQuickReplies(): List<Pair<String, String>> = listOf(
        "Conduciendo" to "Estoy conduciendo, te respondo luego.",
        "Después" to "Ahora no puedo, hablamos luego.",
        "OK" to "OK, entendido."
    )
}
