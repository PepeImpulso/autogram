package com.autogram.app.ui.phone

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.domain.model.SummaryEngineType
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.domain.usecase.ExportImportUseCase
import com.autogram.app.infrastructure.platform.LocaleHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val settingsRepository: SettingsRepository,
    private val conversationRepository: ConversationRepository,
    private val exportImportUseCase: ExportImportUseCase
) : AndroidViewModel(application) {

    sealed class BackupEvent {
        data class ExportReady(val bytes: ByteArray) : BackupEvent()
        data class ExportFailed(val message: String) : BackupEvent()
        data class ImportSuccess(val languageTag: String) : BackupEvent()
        object ImportWrongPassword : BackupEvent()
        object ImportCorrupt : BackupEvent()
        object ImportUnsupportedVersion : BackupEvent()
        data class ImportFailed(val message: String) : BackupEvent()
    }

    private val _backupEvents = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 4)
    val backupEvents: SharedFlow<BackupEvent> = _backupEvents

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsRepository.get()
            _uiState.value = _uiState.value.copy(
                retentionDays = settings.retentionDays,
                autoPlay = settings.autoPlayReplies,
                minDuration = settings.minRecordingDurationMs,
                maxRetries = settings.maxRetryAttempts,
                playbackSpeed = settings.playbackSpeed,
                recordingCuesVolume = settings.recordingCuesVolume,
                languageTag = settings.languageTag,
                autoDeletePlayed = settings.autoDeletePlayed,
                hapticEnabled = settings.hapticEnabled,
                previewBeforeSend = settings.previewBeforeSend,
                biometricLockEnabled = settings.biometricLockEnabled,
                transcribeIncomingVoice = settings.transcribeIncomingVoice,
                showPendingBadges = settings.showPendingBadges,
                summarizeLongMessages = settings.summarizeLongMessages,
                summaryEngine = settings.summaryEngine,
                summaryLlmEndpoint = settings.summaryLlmEndpoint,
                summaryLlmApiKey = settings.summaryLlmApiKey,
                summaryLlmModel = settings.summaryLlmModel,
                keepScreenOn = settings.keepScreenOn,
                developerMode = settings.developerMode
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

    fun updateAutoDeletePlayed(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(autoDeletePlayed = enabled)
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(autoDeletePlayed = enabled))
        }
    }

    fun updateHaptic(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(hapticEnabled = enabled)
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(hapticEnabled = enabled))
        }
    }

    fun updatePreviewBeforeSend(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(previewBeforeSend = enabled)
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(previewBeforeSend = enabled))
        }
    }

    fun updateBiometricLock(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(biometricLockEnabled = enabled)
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(biometricLockEnabled = enabled))
        }
    }

    fun updateTranscribe(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(transcribeIncomingVoice = enabled)
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(transcribeIncomingVoice = enabled))
        }
    }

    fun updateShowPendingBadges(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(showPendingBadges = enabled)
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(showPendingBadges = enabled))
        }
    }

    fun updateSummarize(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(summarizeLongMessages = enabled)
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(summarizeLongMessages = enabled))
        }
    }

    fun updateSummaryEngine(engine: SummaryEngineType) {
        _uiState.value = _uiState.value.copy(summaryEngine = engine)
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(summaryEngine = engine))
        }
    }

    fun updateSummaryLlmEndpoint(endpoint: String) {
        // Trim, reject control chars, normalise scheme. Empty is valid (= disabled).
        val trimmed = endpoint.trim().filter { it.code >= 0x20 }
        val normalised = when {
            trimmed.isEmpty() -> ""
            trimmed.length > 2048 -> trimmed.take(2048)
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "https://$trimmed"
        }
        _uiState.value = _uiState.value.copy(summaryLlmEndpoint = normalised)
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(summaryLlmEndpoint = normalised))
        }
    }

    fun updateSummaryLlmApiKey(apiKey: String) {
        // Trim and cap length. Most provider keys are under 256 chars; 4096 is
        // a generous DoS cap. Strip control chars (newlines, tabs) that would
        // corrupt the Authorization header at request time.
        val sanitised = apiKey.trim().filter { it.code >= 0x20 }.take(4096)
        _uiState.value = _uiState.value.copy(summaryLlmApiKey = sanitised)
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(summaryLlmApiKey = sanitised))
        }
    }

    fun updateKeepScreenOn(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(keepScreenOn = enabled)
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(keepScreenOn = enabled))
        }
    }

    fun updateDeveloperMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(developerMode = enabled)
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(developerMode = enabled))
        }
    }

    fun updateSummaryLlmModel(model: String) {
        // Provider model names are short identifiers (e.g. gpt-4o-mini).
        val sanitised = model.trim().filter { it.code >= 0x20 }.take(128)
        _uiState.value = _uiState.value.copy(summaryLlmModel = sanitised)
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(summaryLlmModel = sanitised))
        }
    }

    fun updateLanguage(tag: String) {
        // BCP-47 sanity: empty (= follow system) or alpha-only language with
        // optional `-REGION-…` subtags. Reject anything else so malformed
        // tags can't break LocaleListCompat.forLanguageTags downstream.
        val candidate = tag.trim()
        val safe = if (candidate.isEmpty() || BCP47_RE.matches(candidate)) candidate else ""
        _uiState.value = _uiState.value.copy(languageTag = safe)
        // Persist: LocaleHelper's SharedPreferences is the source of truth for
        // `attachBaseContext` (our non-AppCompat activities); the DB mirror is
        // just so the row is consistent if someone inspects it.
        LocaleHelper.saveTag(getApplication(), safe)
        viewModelScope.launch {
            val settings = settingsRepository.get()
            settingsRepository.update(settings.copy(languageTag = safe))
        }
        // Tell AppCompat too — this integrates with the system per-app language
        // picker on API 33+ and keeps the backport SharedPreferences in sync.
        val locales = if (safe.isBlank()) LocaleListCompat.getEmptyLocaleList()
                      else LocaleListCompat.forLanguageTags(safe)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    private companion object {
        // Conservative BCP-47 subset: 2-3 letter language, optional 2-letter or
        // 3-digit region, optional alpha subtags. Covers `es`, `es-ES`, `zh-Hant-TW`.
        private val BCP47_RE = Regex(
            "^[a-zA-Z]{2,3}(-[A-Za-z0-9]{2,8})*$"
        )
    }

    fun requestExport(password: CharArray) {
        viewModelScope.launch {
            try {
                val bytes = exportImportUseCase.export(password)
                _backupEvents.emit(BackupEvent.ExportReady(bytes))
            } catch (e: Exception) {
                _backupEvents.emit(BackupEvent.ExportFailed(e.message ?: "unknown error"))
            } finally {
                java.util.Arrays.fill(password, ' ')
            }
        }
    }

    fun requestImport(bytes: ByteArray, password: CharArray) {
        viewModelScope.launch {
            try {
                when (val result = exportImportUseCase.import(bytes, password)) {
                    is ExportImportUseCase.ImportResult.Success -> {
                        // Persist restored language so attachBaseContext picks it up.
                        val tag = result.restored.settings.languageTag
                        LocaleHelper.saveTag(getApplication(), tag)
                        // Also mirror the restored language into our UI state so
                        // the language selector reflects it without a re-fetch.
                        _uiState.value = _uiState.value.copy(languageTag = tag)
                        _backupEvents.emit(BackupEvent.ImportSuccess(tag))
                    }
                    ExportImportUseCase.ImportResult.WrongPassword ->
                        _backupEvents.emit(BackupEvent.ImportWrongPassword)
                    ExportImportUseCase.ImportResult.Corrupt ->
                        _backupEvents.emit(BackupEvent.ImportCorrupt)
                    ExportImportUseCase.ImportResult.UnsupportedVersion ->
                        _backupEvents.emit(BackupEvent.ImportUnsupportedVersion)
                    is ExportImportUseCase.ImportResult.Error ->
                        _backupEvents.emit(BackupEvent.ImportFailed(result.message))
                }
            } finally {
                java.util.Arrays.fill(password, ' ')
            }
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
            _uiState.value = state.copy(lastSaveMessage = "Settings saved")
        }
    }
}

data class SettingsUiState(
    val retentionDays: Int = 30,
    val autoPlay: Boolean = true,
    val minDuration: Long = 1500,
    val maxRetries: Int = 3,
    val playbackSpeed: Float = 1.25f,
    val recordingCuesVolume: Int = 50,
    val languageTag: String = "",
    val autoDeletePlayed: Boolean = true,
    val hapticEnabled: Boolean = true,
    val previewBeforeSend: Boolean = false,
    val biometricLockEnabled: Boolean = false,
    val transcribeIncomingVoice: Boolean = false,
    val showPendingBadges: Boolean = false,
    val summarizeLongMessages: Boolean = false,
    val summaryEngine: SummaryEngineType = SummaryEngineType.NONE,
    val summaryLlmEndpoint: String = "",
    val summaryLlmApiKey: String = "",
    val summaryLlmModel: String = "gpt-4o-mini",
    val keepScreenOn: Boolean = false,
    val developerMode: Boolean = false,
    val lastSaveMessage: String? = null,
    val hasTarget: Boolean = false,
    val targetTitle: String? = null,
    val targetTopicName: String? = null
)
