package com.autogram.app.ui.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.*
import androidx.core.graphics.drawable.IconCompat
import com.autogram.app.R
import com.autogram.app.domain.model.VoiceCommand
import com.autogram.app.domain.repository.QuickReplyRepository
import com.autogram.app.domain.usecase.ManageSettingsUseCase
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.domain.usecase.SendVoiceMtprotoUseCase
import com.autogram.app.infrastructure.platform.VoiceCommandRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Pantalla principal de Android Auto.
 * Muestra botón de grabación, respuestas rápidas y estado de los mensajes.
 * Diseñada según las directrices de distracción optimizada de Google.
 */
class MainScreen(
    carContext: CarContext,
    private val sendMessageUseCase: SendVoiceMtprotoUseCase,
    private val playReplyUseCase: PlayReplyUseCase,
    private val manageSettingsUseCase: ManageSettingsUseCase,
    private val quickReplyRepository: QuickReplyRepository,
    private val voiceCommandRecognizer: VoiceCommandRecognizer
) : Screen(carContext) {

    // BUG-44 FIX: Track job for cleanup when screen is destroyed
    private val screenJob = SupervisorJob()
    private val screenScope = CoroutineScope(Dispatchers.Main + screenJob)
    // BUG-54 FIX: Track reset job to cancel on new interactions
    private var resetJob: kotlinx.coroutines.Job? = null

    private val silentMode = MutableStateFlow(false)
    private val cachedQuickReplies = MutableStateFlow<List<Pair<String, String>>>(defaultQuickReplies())

    init {
        // Cancel coroutines when the screen is destroyed by the host.
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                screenJob.cancel()
            }
        })
        screenScope.launch {
            sendMessageUseCase.status.collect { invalidate() }
        }
        screenScope.launch {
            manageSettingsUseCase.observe().collect {
                if (silentMode.value != it.silentMode) {
                    silentMode.value = it.silentMode
                    invalidate()
                }
            }
        }
        screenScope.launch {
            quickReplyRepository.observeAll().collect { list ->
                val mapped = list.map { it.label to it.textContent }
                cachedQuickReplies.value = mapped.ifEmpty { defaultQuickReplies() }
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        return when (val status = sendMessageUseCase.status.value) {
            is SendVoiceMtprotoUseCase.SendStatus.Recording -> buildRecordingTemplate()
            is SendVoiceMtprotoUseCase.SendStatus.Sending -> buildStatusTemplate(carContext.getString(R.string.status_sending))
            is SendVoiceMtprotoUseCase.SendStatus.Sent -> {
                resetJob?.cancel()
                resetJob = screenScope.launch {
                    delay(3000)
                    sendMessageUseCase.reset()
                    invalidate()
                }
                buildStatusTemplate(carContext.getString(R.string.status_sent))
            }
            is SendVoiceMtprotoUseCase.SendStatus.Discarded -> buildStatusTemplate("Mensaje descartado")
            is SendVoiceMtprotoUseCase.SendStatus.Error -> buildStatusTemplate(
                carContext.getString(R.string.status_error) + ": ${status.message}"
            )
            else -> buildMainGrid()
        }
    }

    private fun buildMainGrid(): GridTemplate {
        val builder = GridTemplate.Builder()
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(buildActionStrip())

        val items = ItemList.Builder()

        val constraintManager = carContext.getCarService(ConstraintManager::class.java)
        val maxItems = constraintManager.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
        var itemCount = 0

        // Record button (primary action) — colored mic
        items.addItem(
            GridItem.Builder()
                .setTitle(carContext.getString(R.string.aa_record))
                .setImage(carIcon(R.drawable.ic_mic, CarColor.PRIMARY))
                .setOnClickListener { startRecording() }
                .build()
        )
        itemCount++

        // Quick replies
        for ((label, text) in cachedQuickReplies.value) {
            if (itemCount >= maxItems) break
            items.addItem(
                GridItem.Builder()
                    .setTitle(label)
                    .setImage(carIcon(R.drawable.ic_quick_reply, CarColor.SECONDARY))
                    .setOnClickListener { sendQuickReply(text) }
                    .build()
            )
            itemCount++
        }

        // Pending count tile (only when there are pending replies)
        val pendingCount = playReplyUseCase.pendingCount()
        if (pendingCount > 0 && itemCount < maxItems) {
            items.addItem(
                GridItem.Builder()
                    .setTitle(carContext.getString(R.string.aa_pending_count, pendingCount))
                    .setImage(carIcon(R.drawable.ic_inbox, CarColor.YELLOW))
                    .setOnClickListener {
                        screenScope.launch { playReplyUseCase.playAllPending() }
                        invalidate()
                    }
                    .build()
            )
        }

        builder.setSingleList(items.build())
        return builder.build()
    }

    private fun buildActionStrip(): ActionStrip {
        val isSilent = silentMode.value
        val silentIcon = if (isSilent) R.drawable.ic_silent else R.drawable.ic_volume_up
        val silentLabel = carContext.getString(
            if (isSilent) R.string.aa_silent_off else R.string.aa_silent_on
        )
        return ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(carIcon(silentIcon, if (isSilent) CarColor.YELLOW else CarColor.DEFAULT))
                    .setTitle(silentLabel)
                    .setOnClickListener { toggleSilent() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(carIcon(R.drawable.ic_voice_command, CarColor.BLUE))
                    .setTitle(carContext.getString(R.string.aa_voice_command))
                    .setOnClickListener { triggerVoiceCommand() }
                    .build()
            )
            .build()
    }

    private fun triggerVoiceCommand() {
        if (!voiceCommandRecognizer.isAvailable()) {
            screenManager.push(VoiceCommandsHelpScreen(carContext))
            return
        }
        screenScope.launch {
            val command = voiceCommandRecognizer.listenOnce()
            dispatchVoiceCommand(command)
        }
    }

    private fun dispatchVoiceCommand(command: VoiceCommand) {
        Timber.d("VoiceCommand recibido: $command")
        when (command) {
            VoiceCommand.REPETIR -> screenScope.launch { playReplyUseCase.playAllPending() }
            VoiceCommand.CANCELAR -> screenScope.launch { sendMessageUseCase.cancelRecording(); invalidate() }
            VoiceCommand.PAUSAR -> { playReplyUseCase.pause(); invalidate() }
            VoiceCommand.REANUDAR -> { playReplyUseCase.resume(); invalidate() }
            VoiceCommand.ENVIAR -> stopAndSend()
            VoiceCommand.SILENCIO -> toggleSilent()
            VoiceCommand.UNKNOWN -> screenManager.push(VoiceCommandsHelpScreen(carContext))
        }
    }

    private fun buildRecordingTemplate(): MessageTemplate {
        return MessageTemplate.Builder(carContext.getString(R.string.status_recording))
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .setIcon(carIcon(R.drawable.ic_mic, CarColor.RED))
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.aa_stop_send))
                    .setIcon(carIcon(R.drawable.ic_send, CarColor.DEFAULT))
                    .setBackgroundColor(CarColor.PRIMARY)
                    .setOnClickListener { stopAndSend() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.aa_cancel))
                    .setIcon(carIcon(R.drawable.ic_cancel, CarColor.DEFAULT))
                    .setBackgroundColor(CarColor.SECONDARY)
                    .setOnClickListener {
                        screenScope.launch { sendMessageUseCase.cancelRecording() }
                        invalidate()
                    }
                    .build()
            )
            .build()
    }

    private fun buildStatusTemplate(message: String): MessageTemplate {
        val (icon, color) = when (sendMessageUseCase.status.value) {
            is SendVoiceMtprotoUseCase.SendStatus.Sent -> R.drawable.ic_send to CarColor.GREEN
            is SendVoiceMtprotoUseCase.SendStatus.Sending -> R.drawable.ic_send to CarColor.BLUE
            is SendVoiceMtprotoUseCase.SendStatus.Error -> R.drawable.ic_cancel to CarColor.RED
            is SendVoiceMtprotoUseCase.SendStatus.Discarded -> R.drawable.ic_cancel to CarColor.YELLOW
            else -> R.drawable.ic_mic to CarColor.DEFAULT
        }
        return MessageTemplate.Builder(message)
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .setIcon(carIcon(icon, color))
            .addAction(
                Action.Builder()
                    .setTitle("Volver")
                    .setOnClickListener {
                        sendMessageUseCase.reset()
                        invalidate()
                    }
                    .build()
            )
            .build()
    }

    private fun startRecording() {
        resetJob?.cancel()
        screenScope.launch {
            val result = sendMessageUseCase.startRecording()
            if (result.isFailure) {
                Timber.e(result.exceptionOrNull(), "Error al iniciar grabación")
            }
            invalidate()
        }
    }

    private fun stopAndSend() {
        resetJob?.cancel()
        screenScope.launch {
            val result = sendMessageUseCase.stopAndSend()
            if (result.isFailure) {
                Timber.e(result.exceptionOrNull(), "Error al enviar")
            }
            invalidate()
        }
    }

    private fun sendQuickReply(text: String) {
        screenScope.launch {
            sendMessageUseCase.sendQuickReply(text)
            invalidate()
        }
    }

    private fun toggleSilent() {
        screenScope.launch {
            manageSettingsUseCase.updateSilentMode(!silentMode.value)
            // observe() in init will call invalidate
        }
    }

    private fun carIcon(resId: Int, tint: CarColor): CarIcon {
        return CarIcon.Builder(IconCompat.createWithResource(carContext, resId))
            .setTint(tint)
            .build()
    }

    private fun defaultQuickReplies(): List<Pair<String, String>> = listOf(
        carContext.getString(R.string.quick_reply_driving) to "Estoy conduciendo, te respondo luego.",
        carContext.getString(R.string.quick_reply_later) to "Ahora no puedo, hablamos luego.",
        carContext.getString(R.string.quick_reply_ok) to "OK, entendido."
    )
}
