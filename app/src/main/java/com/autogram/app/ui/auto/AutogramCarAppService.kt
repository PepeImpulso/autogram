package com.autogram.app.ui.auto

import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.autogram.app.domain.repository.QuickReplyRepository
import com.autogram.app.domain.usecase.ManageSettingsUseCase
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.domain.usecase.SendMessageUseCase
import com.autogram.app.infrastructure.platform.VoiceCommandRecognizer
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Servicio de Android Auto para Autogram.
 * Gestiona el ciclo de vida de la sesión y proporciona la pantalla principal.
 *
 * Note: CarAppService is not in Hilt's AndroidEntryPoint allowlist (only
 * Activity/Service/Fragment/View/BroadcastReceiver). The Session below uses
 * EntryPointAccessors instead of @AndroidEntryPoint to obtain singletons.
 */
class AutogramCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session = AutogramCarSession()
}

/**
 * Hilt entry point exposing the singletons that the Car Session needs.
 * Resolved on demand from the application context — no @AndroidEntryPoint required.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutogramCarSessionDeps {
    fun sendMessageUseCase(): SendMessageUseCase
    fun playReplyUseCase(): PlayReplyUseCase
    fun manageSettingsUseCase(): ManageSettingsUseCase
    fun quickReplyRepository(): QuickReplyRepository
    fun voiceCommandRecognizer(): VoiceCommandRecognizer
}

class AutogramCarSession : Session() {

    override fun onCreateScreen(intent: android.content.Intent): Screen {
        val deps = EntryPointAccessors.fromApplication(
            carContext.applicationContext,
            AutogramCarSessionDeps::class.java
        )
        return MainScreen(
            carContext,
            deps.sendMessageUseCase(),
            deps.playReplyUseCase(),
            deps.manageSettingsUseCase(),
            deps.quickReplyRepository(),
            deps.voiceCommandRecognizer()
        )
    }
}
