package com.autogram.app.infrastructure.persistence.repository

import com.autogram.app.domain.model.AppSettings
import com.autogram.app.domain.model.QuickReplyFilter
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.infrastructure.persistence.dao.SettingsDao
import com.autogram.app.infrastructure.persistence.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val settingsDao: SettingsDao
) : SettingsRepository {

    override fun observe(): Flow<AppSettings> {
        return settingsDao.observe().map { it?.toDomain() ?: AppSettings() }
    }

    override suspend fun get(): AppSettings {
        return settingsDao.get()?.toDomain() ?: AppSettings()
    }

    override suspend fun update(settings: AppSettings) {
        settingsDao.upsert(settings.toEntity())
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
        summaryLlmApiKey = summaryLlmApiKey,
        summaryLlmModel = summaryLlmModel,
        quickReplyFilter = runCatching { QuickReplyFilter.valueOf(quickReplyFilter) }
            .getOrDefault(QuickReplyFilter.ALL),
        keepScreenOn = keepScreenOn,
        developerMode = developerMode
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
        summaryLlmApiKey = summaryLlmApiKey,
        summaryLlmModel = summaryLlmModel,
        quickReplyFilter = quickReplyFilter.name,
        keepScreenOn = keepScreenOn,
        developerMode = developerMode
    )
}
