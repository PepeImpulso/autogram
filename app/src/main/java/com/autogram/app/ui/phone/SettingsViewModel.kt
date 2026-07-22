package com.autogram.app.ui.phone

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.domain.model.AudioFocusMode
import com.autogram.app.domain.model.SummaryEngineType
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.domain.usecase.ExportImportUseCase
import com.autogram.app.infrastructure.audio.AudioPlayer
import com.autogram.app.infrastructure.audio.PlaybackSpeed
import com.autogram.app.infrastructure.audio.PlaybackDiagnostics
import com.autogram.app.infrastructure.audio.TtsPlayer
import com.autogram.app.infrastructure.platform.LocaleHelper
import com.autogram.app.infrastructure.privacy.LocalDataEraser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val settingsRepository: SettingsRepository,
    private val conversationRepository: ConversationRepository,
    private val exportImportUseCase: ExportImportUseCase,
    // v1.24.0 — wired for the "Test playback" button in the AA audio routing
    // section. v1.24.1 — diagnostics row now reads from [PlaybackDiagnostics]
    // (covers both AudioPlayer + TtsPlayer) instead of AudioPlayer-only.
    private val audioPlayer: AudioPlayer,
    private val ttsPlayer: TtsPlayer,
    private val playbackDiagnostics: PlaybackDiagnostics,
    private val localDataEraser: LocalDataEraser
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

    sealed class DataEraseEvent {
        object Success : DataEraseEvent()
        data class Failed(val message: String) : DataEraseEvent()
    }

    private val _dataEraseEvents = MutableSharedFlow<DataEraseEvent>(extraBufferCapacity = 1)
    val dataEraseEvents: SharedFlow<DataEraseEvent> = _dataEraseEvents

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
                developerMode = settings.developerMode,
                audioFocusMode = settings.audioFocusMode,
                ttsAsNavigationPrompt = settings.ttsAsNavigationPrompt
            )
        }
        viewModelScope.launch {
            playbackDiagnostics.lastAttempt.collect { attempt ->
                _uiState.value = _uiState.value.copy(lastPlaybackAttempt = attempt)
            }
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

    fun eraseLocalData() {
        viewModelScope.launch {
            localDataEraser.erase().fold(
                onSuccess = { _dataEraseEvents.emit(DataEraseEvent.Success) },
                onFailure = { error ->
                    Timber.e(error, "Local data erasure failed")
                    _dataEraseEvents.emit(
                        DataEraseEvent.Failed(error.message ?: "unknown error")
                    )
                }
            )
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
        val clamped = PlaybackSpeed.coerce(speed)
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

    fun updateAudioFocusMode(mode: AudioFocusMode) {
        _uiState.value = _uiState.value.copy(audioFocusMode = mode)
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(audioFocusMode = mode))
        }
    }

    fun updateTtsAsNavigationPrompt(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(ttsAsNavigationPrompt = enabled)
        viewModelScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(ttsAsNavigationPrompt = enabled))
        }
    }

    /**
     * v1.24.0 — fires a short test tone through AudioPlayer (exercises the
     * voice-message path) followed by a TTS sample (exercises the text-message
     * path), both using the currently-selected audio focus mode + TTS routing
     * toggle. Intended to be tapped in the parked car: cycle through modes,
     * tap the button, listen for the result through the car speakers.
     *
     * The tone is a 600 ms 440 Hz sine synthesised inline and dropped into
     * cacheDir, so no bundled asset is shipped.
     */
    fun runTestPlayback() {
        viewModelScope.launch {
            val settings = settingsRepository.get()
            try {
                val toneFile = synthesiseTestTone()
                audioPlayer.play(
                    toneFile,
                    speed = settings.playbackSpeed,
                    focusMode = settings.audioFocusMode,
                    useNavigationUsage = settings.ttsAsNavigationPrompt
                ) {
                    // Once the tone finishes, speak a short test phrase so the
                    // user also hears the TTS path. TTS reads the current
                    // [AppSettings.ttsAsNavigationPrompt] from the same
                    // settingsRepository instance.
                    viewModelScope.launch {
                        ttsPlayer.speak(
                            getApplication<Application>().getString(
                                com.autogram.app.R.string.settings_aa_routing_test_phrase
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Test playback failed")
            }
        }
    }

    /**
     * Generates a 600 ms, 440 Hz, 50 % amplitude 16-bit mono PCM WAV in
     * cacheDir and returns the resulting File. Idempotent — the file is
     * rewritten on every call so we always have a fresh, valid tone even
     * if cacheDir was wiped by the OS.
     */
    private fun synthesiseTestTone(): File {
        val sampleRate = 44_100
        val durationMs = 600
        val numSamples = sampleRate * durationMs / 1000
        val pcm = ByteArray(numSamples * 2)
        val amplitude = (Short.MAX_VALUE * 0.5).toInt()
        for (i in 0 until numSamples) {
            val sample = (kotlin.math.sin(2.0 * Math.PI * i * 440.0 / sampleRate) * amplitude).toInt()
            pcm[i * 2] = (sample and 0xff).toByte()
            pcm[i * 2 + 1] = ((sample shr 8) and 0xff).toByte()
        }
        val dataLen = pcm.size
        val totalLen = 36 + dataLen
        val header = ByteArray(44).apply {
            // RIFF header
            this[0] = 'R'.code.toByte(); this[1] = 'I'.code.toByte()
            this[2] = 'F'.code.toByte(); this[3] = 'F'.code.toByte()
            writeIntLE(this, 4, totalLen)
            this[8] = 'W'.code.toByte(); this[9] = 'A'.code.toByte()
            this[10] = 'V'.code.toByte(); this[11] = 'E'.code.toByte()
            // fmt subchunk
            this[12] = 'f'.code.toByte(); this[13] = 'm'.code.toByte()
            this[14] = 't'.code.toByte(); this[15] = ' '.code.toByte()
            writeIntLE(this, 16, 16)        // subchunk1 size (PCM)
            writeShortLE(this, 20, 1)       // PCM format
            writeShortLE(this, 22, 1)       // mono
            writeIntLE(this, 24, sampleRate)
            writeIntLE(this, 28, sampleRate * 2) // byte rate
            writeShortLE(this, 32, 2)       // block align
            writeShortLE(this, 34, 16)      // bits per sample
            // data subchunk
            this[36] = 'd'.code.toByte(); this[37] = 'a'.code.toByte()
            this[38] = 't'.code.toByte(); this[39] = 'a'.code.toByte()
            writeIntLE(this, 40, dataLen)
        }
        val file = File(getApplication<Application>().cacheDir, "audio_routing_test_tone.wav")
        file.writeBytes(header + pcm)
        return file
    }

    private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xff).toByte()
        buf[offset + 1] = ((value shr 8) and 0xff).toByte()
        buf[offset + 2] = ((value shr 16) and 0xff).toByte()
        buf[offset + 3] = ((value shr 24) and 0xff).toByte()
    }

    private fun writeShortLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xff).toByte()
        buf[offset + 1] = ((value shr 8) and 0xff).toByte()
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
    val targetTopicName: String? = null,
    val audioFocusMode: AudioFocusMode = AudioFocusMode.TRANSIENT_MAY_DUCK,
    val ttsAsNavigationPrompt: Boolean = false,
    // v1.24.1 — the last playback attempt (file or TTS), with source +
    // focus result + routed device + focus mode + timestamp. Null = no
    // playback yet this session.
    val lastPlaybackAttempt: PlaybackDiagnostics.PlaybackAttempt? = null
)
