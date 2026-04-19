package com.autogram.app.domain.usecase

import com.autogram.app.domain.model.AppSettings
import com.autogram.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Caso de uso: gestionar la configuración de la aplicación.
 */
class ManageSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    fun observe(): Flow<AppSettings> = settingsRepository.observe()
    suspend fun get(): AppSettings = settingsRepository.get()
    suspend fun update(settings: AppSettings) = settingsRepository.update(settings)

    // BUG-30: Removed empty updateBotToken() — tokens are managed via ConversationRepository

    suspend fun updateSilentMode(enabled: Boolean) {
        val settings = settingsRepository.get()
        settingsRepository.update(settings.copy(silentMode = enabled))
    }

    suspend fun updateAutoPlay(enabled: Boolean) {
        val settings = settingsRepository.get()
        settingsRepository.update(settings.copy(autoPlayReplies = enabled))
    }

    suspend fun updateRetentionDays(days: Int) {
        val settings = settingsRepository.get()
        settingsRepository.update(settings.copy(retentionDays = days))
    }
}
