package com.autogram.app.ui.phone

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val settingsRepository: SettingsRepository,
    private val conversationRepository: ConversationRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsRepository.get()
            _uiState.value = _uiState.value.copy(
                retentionDays = settings.retentionDays,
                silentMode = settings.silentMode,
                autoPlay = settings.autoPlayReplies,
                minDuration = settings.minRecordingDurationMs,
                maxRetries = settings.maxRetryAttempts,
                playbackSpeed = settings.playbackSpeed,
                recordingCuesVolume = settings.recordingCuesVolume,
                languageTag = settings.languageTag
            )
        }
        viewModelScope.launch {
            conversationRepository.observeActive().collect { target ->
                _uiState.value = _uiState.value.copy(
                    targetTitle = target?.targetTitle,
                    targetTopicName = target?.topicName,
                    hasTarget = target != null
                )
            }
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

    fun updatePlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        _uiState.value = _uiState.value.copy(playbackSpeed = clamped)
        viewModelScope.launch {
            val settings = settingsRepository.get()
            settingsRepository.update(settings.copy(playbackSpeed = clamped))
        }
    }

    fun updateRecordingCuesVolume(volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        _uiState.value = _uiState.value.copy(recordingCuesVolume = clamped)
        viewModelScope.launch {
            val settings = settingsRepository.get()
            settingsRepository.update(settings.copy(recordingCuesVolume = clamped))
        }
    }

    fun updateLanguage(tag: String) {
        _uiState.value = _uiState.value.copy(languageTag = tag)
        viewModelScope.launch {
            val settings = settingsRepository.get()
            settingsRepository.update(settings.copy(languageTag = tag))
        }
        // Apply immediately so the activity recreates with the new strings.
        // Empty tag → follow system (clearing the per-app override).
        val locales = if (tag.isBlank()) LocaleListCompat.getEmptyLocaleList()
                      else LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(locales)
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
    val playbackSpeed: Float = 1.25f,
    val recordingCuesVolume: Int = 50,
    val languageTag: String = "",
    val lastSaveMessage: String? = null,
    val hasTarget: Boolean = false,
    val targetTitle: String? = null,
    val targetTopicName: String? = null
)
