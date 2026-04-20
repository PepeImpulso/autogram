package com.autogram.app.ui.phone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val settingsRepository: SettingsRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsRepository.get()
            _uiState.value = SettingsUiState(
                retentionDays = settings.retentionDays,
                silentMode = settings.silentMode,
                autoPlay = settings.autoPlayReplies,
                minDuration = settings.minRecordingDurationMs,
                maxRetries = settings.maxRetryAttempts
            )
        }
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

    fun saveSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            val settings = settingsRepository.get()
            settingsRepository.update(settings.copy(
                retentionDays = state.retentionDays,
                maxRetryAttempts = state.maxRetries,
                minRecordingDurationMs = state.minDuration
            ))
            _uiState.value = state.copy(lastSaveMessage = "Configuración guardada ✓")
        }
    }
}

data class SettingsUiState(
    val retentionDays: Int = 30,
    val silentMode: Boolean = false,
    val autoPlay: Boolean = true,
    val minDuration: Long = 1500,
    val maxRetries: Int = 3,
    val lastSaveMessage: String? = null
)
