package com.autogram.app.ui.auto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.QuickReplyRepository
import com.autogram.app.domain.usecase.ManageSettingsUseCase
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.domain.usecase.SendVoiceMtprotoUseCase
import com.autogram.app.infrastructure.platform.VoiceCommandRecognizer
import com.autogram.app.service.MtprotoReceiverService
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

    override fun onCreate() {
        super.onCreate()
        // Shows up in logcat as `AutogramCarAppService  onCreate` when
        // Android Auto actually discovers and instantiates the service.
        // Absence in logcat during an AA connect is a strong signal that
        // the manifest isn't being matched — e.g. the user hasn't enabled
        // "Unknown sources" in Android Auto → Developer Settings.
        timber.log.Timber.i("AutogramCarAppService onCreate — AA instantiated the service")

        // Start MtprotoReceiverService so TDLib is authenticated and
        // ready before the user taps Record on the car screen. Without
        // this, launching Autogram *only* via Android Auto (without ever
        // opening MainActivity on the phone) leaves TDLib un-initialised
        // — voice messages get recorded, validated, and silently queue
        // offline forever because authState never reaches Ready.
        // startForegroundService is idempotent: if the service is
        // already running (e.g. MainActivity was opened earlier) this
        // is a no-op.
        runCatching {
            val intent = Intent(this, MtprotoReceiverService::class.java).apply {
                action = MtprotoReceiverService.ACTION_START
            }
            ContextCompat.startForegroundService(this, intent)
        }.onFailure {
            timber.log.Timber.w(it, "Failed to start MtprotoReceiverService from CarApp context")
        }
    }

    override fun createHostValidator(): HostValidator {
        // Release builds use the release-signed AA allowlist; debug builds
        // allow any host so the DHU (Desktop Head Unit) can connect.
        return if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(com.autogram.app.R.array.car_hosts_allowlist)
                .build()
        }
    }

    override fun onCreateSession(): Session {
        timber.log.Timber.i("AutogramCarAppService onCreateSession — AA host opened the app")
        return AutogramCarSession()
    }
}

/**
 * Hilt entry point exposing the singletons that the Car Session needs.
 * Resolved on demand from the application context — no @AndroidEntryPoint required.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutogramCarSessionDeps {
    fun sendMessageUseCase(): SendVoiceMtprotoUseCase
    fun playReplyUseCase(): PlayReplyUseCase
    fun manageSettingsUseCase(): ManageSettingsUseCase
    fun quickReplyRepository(): QuickReplyRepository
    fun conversationRepository(): ConversationRepository
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
            deps.conversationRepository(),
            deps.voiceCommandRecognizer()
        )
    }
}
