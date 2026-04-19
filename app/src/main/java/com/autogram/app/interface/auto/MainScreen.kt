package com.autogram.app.interface.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.*
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.domain.usecase.SendMessageUseCase

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

    // Observamos el estado del envío
    private var lastStatus: SendMessageUseCase.SendStatus = SendMessageUseCase.SendStatus.Idle

    override fun onGetTemplate(): Template {
        return when (val status = sendMessageUseCase.currentStatus.value) {
            is SendMessageUseCase.SendStatus.Recording -> buildRecordingTemplate()
            is SendMessageUseCase.SendStatus.Sending -> buildStatusTemplate("Enviando...")
            is SendMessageUseCase.SendStatus.Sent -> buildStatusTemplate("Enviado ✓")
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

        // Botón de parar (si está grabando)
        if (sendMessageUseCase.currentStatus.value is SendMessageUseCase.SendStatus.Recording) {
            items.addItem(
                GridItem.Builder()
                    .setTitle("Parar")
                    .setOnClick {
                        stopAndSend()
                    }
                    .build()
            )
        }

        // Respuestas rápidas
        listOf("Conduciendo" to "Estoy conduciendo, te respondo luego.",
            "Después" to "Ahora no puedo, hablamos luego.",
            "OK" to "OK, entendido."
        ).forEach { (label, text) ->
            items.addItem(
                GridItem.Builder()
                    .setTitle(label)
                    .setOnClick {
                        sendQuickReply(text)
                    }
                    .build()
            )
        }

        // Indicador de pendientes
        val pendingCount = playReplyUseCase.pendingCount()
        if (pendingCount > 0) {
            items.addItem(
                GridItem.Builder()
                    .setTitle("$pendingCount pendientes")
                    .setOnClick {
                        // Reproducir pendientes
                        kotlinx.coroutines.runBlocking {
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
                        kotlinx.coroutines.runBlocking {
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

    private fun startRecording() {
        kotlinx.coroutines.runBlocking {
            val result = sendMessageUseCase.startRecording(carContext)
            if (result.isFailure) {
                Timber.e(result.exceptionOrNull(), "Error al iniciar grabación")
            }
        }
        invalidate()
    }

    private fun stopAndSend() {
        kotlinx.coroutines.runBlocking {
            val result = sendMessageUseCase.stopAndSend()
            if (result.isFailure) {
                Timber.e(result.exceptionOrNull(), "Error al enviar")
            }
        }
        invalidate()
    }

    private fun sendQuickReply(text: String) {
        kotlinx.coroutines.runBlocking {
            sendMessageUseCase.sendQuickReply(text)
        }
        invalidate()
    }
}

// Re-export para Timber en este archivo
private object Timber {
    fun e(e: Throwable?, msg: String) = timber.log.Timber.e(e, msg)
}
