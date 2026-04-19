package com.autogram.app.interface.auto

import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.autogram.app.domain.repository.QuickReplyRepository
import com.autogram.app.domain.usecase.ManageSettingsUseCase
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.domain.usecase.SendMessageUseCase
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Servicio de Android Auto para Autogram.
 * Gestiona el ciclo de vida de la sesión y proporciona la pantalla principal.
 */
@AndroidEntryPoint
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

    override fun onCreateSession(): Session {
        return AutogramCarSession()
    }
}

/**
 * Sesión de Android Auto. Crea la pantalla principal.
 */
@AndroidEntryPoint
class AutogramCarSession : Session() {

    @Inject lateinit var sendMessageUseCase: SendMessageUseCase
    @Inject lateinit var playReplyUseCase: PlayReplyUseCase
    @Inject lateinit var manageSettingsUseCase: ManageSettingsUseCase
    @Inject lateinit var quickReplyRepository: QuickReplyRepository

    // BUG-14: Handle deep links from notifications
    override fun onCreateScreen(intent: android.content.Intent): Screen {
        // TODO: Handle intent.action for deep navigation (e.g., open specific message)
        return MainScreen(
            carContext,
            sendMessageUseCase,
            playReplyUseCase,
            manageSettingsUseCase,
            quickReplyRepository
        )
    }
}
