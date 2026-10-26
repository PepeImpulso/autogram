package com.autogram.app.infrastructure.persistence.repository

import com.autogram.app.domain.model.AppSettings
import com.autogram.app.domain.model.AudioFocusMode
import com.autogram.app.domain.model.QuickReplyFilter
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.infrastructure.persistence.dao.SettingsDao
import com.autogram.app.infrastructure.persistence.entity.SettingsEntity
import com.autogram.app.infrastructure.security.LlmApiKeyStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val settingsDao: SettingsDao,
    private val llmApiKeyStore: LlmApiKeyStore
) : SettingsRepository {

    override fun observe(): Flow<AppSettings> {
        return settingsDao.observe().map { it?.toDomain() ?: AppSettings() }
    }

    override suspend fun get(): AppSettings {
        val entity = settingsDao.get() ?: return AppSettings()
        migrateLegacyApiKeyIfNeeded(entity)
        return entity.toDomain()
    }

    override suspend fun update(settings: AppSettings) {
        // Sink the API key to the encrypted store; never persist it through
        // Room. settings.summaryLlmApiKey carries the user's intent; the
        // entity row stores "" so a Room dump / backup leak can't expose it.
        llmApiKeyStore.set(settings.summaryLlmApiKey)
        settingsDao.upsert(settings.toEntity())
    }

    /**
     * Audit issue #70: copy a pre-existing plaintext API key from Room into
     * EncryptedSharedPreferences on first observation, then blank the Room
     * column so the next dump / backup / forensic extract can't surface it.
     * No-op when the Room column is already empty. Idempotent — safe to call
     * on every read.
     */
    private suspend fun migrateLegacyApiKeyIfNeeded(entity: SettingsEntity) {
        val legacy = entity.summaryLlmApiKey
        if (legacy.isEmpty()) return
        if (!llmApiKeyStore.isPopulated()) {
            llmApiKeyStore.set(legacy)
        }
        // Always blank the Room column once we've copied the value across,
        // even when the encrypted store was already populated (which means
        // a previous migration ran and Room still happens to have the
        // legacy value — wipe it now).
        runCatching { settingsDao.upsert(entity.copy(summaryLlmApiKey = "")) }
            .onFailure { timber.log.Timber.w(it, "Failed to blank legacy LLM key in Room") }
    }

    private fun SettingsEntity.toDomain() = AppSettings(
        id = id, activeConversationId = activeConversationId, retentionDays = retentionDays,
        silentMode = silentMode, minRecordingDurationMs = minRecordingDurationMs,
        maxRecordingDurationMs = maxRecordingDurationMs, pollingIntervalMs = pollingIntervalMs,
        maxRetryAttempts = maxRetryAttempts, autoPlayReplies = autoPlayReplies,
        playbackSpeed = playbackSpeed, recordingCuesVolume = recordingCuesVolume,
        languageTag = languageTag, autoDeletePlayed = autoDeletePlayed,
        hapticEnabled = hapticEnabled, previewBeforeSend = previewBeforeSend,
        biometricLockEnabled = biometricLockEnabled,
        onboardingCompleted = onboardingCompleted,
        transcribeIncomingVoice = transcribeIncomingVoice,
        showPendingBadges = showPendingBadges,
        summarizeLongMessages = summarizeLongMessages,
        summaryEngine = summaryEngine,
        summaryLlmEndpoint = summaryLlmEndpoint,
        // Read from the encrypted store; the Room column is intentionally
        // ignored once migration has run (it stays "" forever after).
        summaryLlmApiKey = llmApiKeyStore.get(),
        summaryLlmModel = summaryLlmModel,
        quickReplyFilter = runCatching { QuickReplyFilter.valueOf(quickReplyFilter) }
            .getOrDefault(QuickReplyFilter.ALL),
        showQuickReplyFilters = showQuickReplyFilters,
        showQuickReplyShortcuts = showQuickReplyShortcuts,
        keepScreenOn = keepScreenOn,
        developerMode = developerMode,
        audioFocusMode = runCatching { AudioFocusMode.valueOf(audioFocusMode) }
            .getOrDefault(AudioFocusMode.TRANSIENT_MAY_DUCK),
        ttsAsNavigationPrompt = ttsAsNavigationPrompt,
        carGridButtons = carGridButtons
    )

    private fun AppSettings.toEntity() = SettingsEntity(
        id = id, activeConversationId = activeConversationId, retentionDays = retentionDays,
        silentMode = silentMode, minRecordingDurationMs = minRecordingDurationMs,
        maxRecordingDurationMs = maxRecordingDurationMs, pollingIntervalMs = pollingIntervalMs,
        maxRetryAttempts = maxRetryAttempts, autoPlayReplies = autoPlayReplies,
        playbackSpeed = playbackSpeed, recordingCuesVolume = recordingCuesVolume,
        languageTag = languageTag, autoDeletePlayed = autoDeletePlayed,
        hapticEnabled = hapticEnabled, previewBeforeSend = previewBeforeSend,
        biometricLockEnabled = biometricLockEnabled,
        onboardingCompleted = onboardingCompleted,
        transcribeIncomingVoice = transcribeIncomingVoice,
        showPendingBadges = showPendingBadges,
        summarizeLongMessages = summarizeLongMessages,
        summaryEngine = summaryEngine,
        summaryLlmEndpoint = summaryLlmEndpoint,
        // Always blank in Room; the real value lives in EncryptedSharedPreferences.
        summaryLlmApiKey = "",
        summaryLlmModel = summaryLlmModel,
        quickReplyFilter = quickReplyFilter.name,
        showQuickReplyFilters = showQuickReplyFilters,
        showQuickReplyShortcuts = showQuickReplyShortcuts,
        keepScreenOn = keepScreenOn,
        developerMode = developerMode,
        audioFocusMode = audioFocusMode.name,
        ttsAsNavigationPrompt = ttsAsNavigationPrompt,
        carGridButtons = carGridButtons
    )
}
