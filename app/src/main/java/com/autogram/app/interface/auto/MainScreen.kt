package com.autogram.app.interface.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.*
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Pantalla principal de Android Auto.
 * Muestra botón de grabación, respuestas rápidas y estado de los mensajes.
 * Diseñada según las directrices de distracción optimizada de Google.
 */
class MainScreen(
    carContext: CarContext,
    private val sendMessageUseCase: SendMessageUseCase,
    private val playReplyUseCase: PlayReplyUseCase
) : Screen(carContext) {

    // BUG-44 FIX: Track job for cleanup when screen is destroyed
    private val screenJob = SupervisorJob()
    private val screenScope = CoroutineScope(Dispatchers.Main + screenJob)
    // BUG-54 FIX: Track reset job to cancel on new interactions
    private var resetJob: kotlinx.coroutines.Job? = null

    // BUG-44 FIX: Cancel coroutines when screen is destroyed
    override fun onDestroy() {
        screenJob.cancel()
        super.onDestroy()
    }

    init {
        // BUG-23: Observe status changes to auto-invalidate the screen
        screenScope.launch {
            sendMessageUseCase.currentStatus.collect {
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        return when (val status = sendMessageUseCase.currentStatus.value) {
            is SendMessageUseCase.SendStatus.Recording -> buildRecordingTemplate()
            is SendMessageUseCase.SendStatus.Sending -> buildStatusTemplate("Enviando...")
            is SendMessageUseCase.SendStatus.Sent -> {
                // BUG-47 FIX: Auto-reset after 3 seconds
                // BUG-54 FIX: Cancel any previous reset job before launching new one
                resetJob?.cancel()
                resetJob = screenScope.launch {
                    delay(3000)
                    sendMessageUseCase.reset()
                    invalidate()
                }
                buildStatusTemplate("Enviado ✓")
            }
            is SendMessageUseCase.SendStatus.Discarded -> buildStatusTemplate("Mensaje descartado")
            is SendMessageUseCase.SendStatus.Error -> buildStatusTemplate("Error: ${status.message}")
            else -> buildMainGrid()
        }
    }

    /**
     * Pantalla principal con grid de acciones.
     */
    private fun buildMainGrid(): GridTemplate {
        val builder = GridTemplate.Builder()
            .setTitle("Autogram")
            .setHeaderAction(Action.APP_ICON)

        val items = ItemList.Builder()

        // BUG-22: Respect ConstraintManager grid item limits
        val constraintManager = carContext.getCarService(ConstraintManager::class.java)
        val maxItems = constraintManager.getContentLimit(ConstraintManager.CONTENT_LIMIT_GRID_ITEMS)
        var itemCount = 0

        // Botón de grabación
        items.addItem(
            GridItem.Builder()
                .setTitle("Grabar")
                .setImage(
                    CarIcon.Builder(
                        androidx.core.graphics.drawable.IconCompat.createWithResource(
                            carContext, android.R.drawable.ic_btn_speak_now
                        )
                    ).build()
                )
                .setOnClick {
                    startRecording()
                }
                .build()
        )
        itemCount++

        // BUG-09: Removed dead "Parar" button from main grid
        // (This code was unreachable since buildMainGrid is only called when NOT recording)

        // Respuestas rápidas
        val quickReplies = listOf(
            "Conduciendo" to "Estoy conduciendo, te respondo luego.",
            "Después" to "Ahora no puedo, hablamos luego.",
            "OK" to "OK, entendido."
        )
        for ((label, text) in quickReplies) {
            if (itemCount >= maxItems) break
            items.addItem(
                GridItem.Builder()
                    .setTitle(label)
                    .setOnClick {
                        sendQuickReply(text)
                    }
                    .build()
            )
            itemCount++
        }

        // Indicador de pendientes
        val pendingCount = playReplyUseCase.pendingCount()
        if (pendingCount > 0 && itemCount < maxItems) {
            items.addItem(
                GridItem.Builder()
                    .setTitle("$pendingCount pendientes")
                    .setOnClick {
                        // BUG-01: Use coroutine instead of runBlocking
                        screenScope.launch {
                            playReplyUseCase.playAllPending()
                        }
                        invalidate()
                    }
                    .build()
            )
        }

        builder.setSingleList(items.build())
        return builder.build()
    }

    /**
     * Pantalla durante la grabación.
     */
    private fun buildRecordingTemplate(): MessageTemplate {
        return MessageTemplate.Builder("Grabando...")
            .setTitle("Autogram")
            .setHeaderAction(Action.APP_ICON)
            .addAction(
                Action.Builder()
                    .setTitle("Parar y enviar")
                    .setBackgroundColor(CarColor.PRIMARY)
                    .setOnClick { stopAndSend() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Cancelar")
                    .setBackgroundColor(CarColor.SECONDARY)
                    .setOnClick {
                        // BUG-01: Use coroutine instead of runBlocking
                        screenScope.launch {
                            sendMessageUseCase.cancelRecording()
                        }
                        invalidate()
                    }
                    .build()
            )
            .build()
    }

    /**
     * Pantalla de estado (enviando, enviado, error).
     */
    private fun buildStatusTemplate(message: String): MessageTemplate {
        return MessageTemplate.Builder(message)
            .setTitle("Autogram")
            .setHeaderAction(Action.APP_ICON)
            .addAction(
                Action.Builder()
                    .setTitle("Volver")
                    .setOnClick {
                        sendMessageUseCase.reset()
                        invalidate()
                    }
                    .build()
            )
            .build()
    }

    // BUG-01: Replaced all runBlocking with coroutine-based async calls
    private fun startRecording() {
        resetJob?.cancel()  // BUG-54 FIX
        screenScope.launch {
            val result = sendMessageUseCase.startRecording(carContext)
            if (result.isFailure) {
                Timber.e(result.exceptionOrNull(), "Error al iniciar grabación")
            }
            invalidate()
        }
    }

    private fun stopAndSend() {
        resetJob?.cancel()  // BUG-54 FIX
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
}
