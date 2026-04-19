package com.autogram.app.ui.phone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.domain.model.AppSettings
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.infrastructure.telegram.TelegramApiClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val settingsRepository: SettingsRepository,
    private val conversationRepository: ConversationRepository,
    private val telegramApiClient: TelegramApiClient
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsRepository.get()
            val conversation = settings.activeConversationId?.let { conversationRepository.getById(it) }
            _uiState.value = SettingsUiState(
                botToken = conversation?.botToken ?: "",
                chatId = conversation?.targetChatId ?: "",
                chatTitle = conversation?.targetTitle ?: "",
                retentionDays = settings.retentionDays,
                silentMode = settings.silentMode,
                autoPlay = settings.autoPlayReplies,
                minDuration = settings.minRecordingDurationMs,
                maxRetries = settings.maxRetryAttempts
            )
        }
    }

    fun updateBotToken(token: String) {
        _uiState.value = _uiState.value.copy(botToken = token)
    }

    fun updateChatId(chatId: String) {
        _uiState.value = _uiState.value.copy(chatId = chatId)
    }

    fun updateChatTitle(title: String) {
        _uiState.value = _uiState.value.copy(chatTitle = title)
    }

    fun updateRetentionDays(days: Int) {
        _uiState.value = _uiState.value.copy(retentionDays = days)
    }

    fun updateSilentMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(silentMode = enabled)
        viewModelScope.launch {
            val settings = settingsRepository.get()
            settingsRepository.update(settings.copy(silentMode = enabled))
        }
    }

    fun updateAutoPlay(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(autoPlay = enabled)
        viewModelScope.launch {
            val settings = settingsRepository.get()
            settingsRepository.update(settings.copy(autoPlayReplies = enabled))
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTesting = true, testResult = null)
            try {
                telegramApiClient.configure(_uiState.value.botToken)
                val result = telegramApiClient.testConnection()
                _uiState.value = _uiState.value.copy(
                    isTesting = false,
                    testResult = if (result.isSuccess) {
                        "Conexión OK: @${result.getOrThrow().username ?: result.getOrThrow().first_name}"
                    } else {
                        "Error: ${result.exceptionOrNull()?.message}"
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isTesting = false,
                    testResult = "Error: ${e.message}"
                )
            }
        }
    }

    fun saveSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.botToken.isBlank() || state.chatId.isBlank()) {
                _uiState.value = state.copy(testResult = "Token y Chat ID son obligatorios")
                return@launch
            }

            // Crear o actualizar la conversación
            val existing = conversationRepository.getActive()
            val conversation = if (existing != null) {
                existing.copy(
                    botToken = state.botToken,
                    targetChatId = state.chatId,
                    targetTitle = state.chatTitle.ifBlank { "Chat ${state.chatId}" }
                )
            } else {
                Conversation(
                    id = UUID.randomUUID().toString(),
                    botToken = state.botToken,
                    targetChatId = state.chatId,
                    targetTitle = state.chatTitle.ifBlank { "Chat ${state.chatId}" },
                    isActive = true,
                    createdAt = System.currentTimeMillis()
                )
            }
            conversationRepository.upsert(conversation)
            conversationRepository.setActive(conversation.id)

            // Actualizar settings
            val settings = settingsRepository.get()
            settingsRepository.update(settings.copy(
                activeConversationId = conversation.id,
                retentionDays = state.retentionDays,
                maxRetryAttempts = state.maxRetries,
                minRecordingDurationMs = state.minDuration
            ))

            _uiState.value = state.copy(testResult = "Configuración guardada ✓")
            Timber.i("Configuración guardada: chatId=${state.chatId}")
        }
    }
}

data class SettingsUiState(
    val botToken: String = "",
    val chatId: String = "",
    val chatTitle: String = "",
    val retentionDays: Int = 30,
    val silentMode: Boolean = false,
    val autoPlay: Boolean = true,
    val minDuration: Long = 1500,
    val maxRetries: Int = 3,
    val isTesting: Boolean = false,
    val testResult: String? = null
)
