package com.autogram.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.autogram.app.R
import com.autogram.app.domain.model.*
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.infrastructure.audio.AudioPlayer
import com.autogram.app.infrastructure.platform.CallStateMonitor
import com.autogram.app.infrastructure.telegram.TelegramApiClient
import com.autogram.app.interface.phone.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * Servicio en primer plano para polling continuo de mensajes de Telegram.
 * Mantiene una conexión long-polling activa y gestiona la reproducción automática.
 */
@AndroidEntryPoint
class AutogramPollingService : Service() {

    companion object {
        const val CHANNEL_ID = "autogram_polling"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.autogram.app.START_POLLING"
        const val ACTION_STOP = "com.autogram.app.STOP_POLLING"

        var isRunning = false
            private set
    }

    @Inject lateinit var telegramApiClient: TelegramApiClient
    @Inject lateinit var conversationRepository: ConversationRepository
    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var playReplyUseCase: PlayReplyUseCase
    @Inject lateinit var callStateMonitor: CallStateMonitor

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null
    private var lastUpdateId: Long = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Conectando..."))
        isRunning = true
        callStateMonitor.start()
        Timber.i("AutogramPollingService creado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopPolling()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startPolling()
            }
            else -> {
                startPolling()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopPolling()
        callStateMonitor.stop()
        serviceScope.cancel()
        isRunning = false
        Timber.i("AutogramPollingService destruido")
        super.onDestroy()
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return

        pollingJob = serviceScope.launch {
            Timber.i("Iniciando polling de Telegram")

            while (isActive) {
                try {
                    val conversation = conversationRepository.getActive()
                    if (conversation == null) {
                        updateNotification("Sin configurar")
                        delay(5000)
                        continue
                    }

                    telegramApiClient.configure(conversation.botToken)
                    updateNotification("Escuchando mensajes...")

                    // Long-polling con timeout de 30 segundos
                    val result = telegramApiClient.getUpdates(
                        offset = if (lastUpdateId > 0) lastUpdateId + 1 else null
                    )

                    if (result.isSuccess) {
                        val updates = result.getOrThrow()
                        for (update in updates) {
                            lastUpdateId = update.update_id
                            processUpdate(update, conversation)
                        }
                    } else {
                        Timber.w(result.exceptionOrNull(), "Error en polling")
                        updateNotification("Error de conexión")
                        delay(5000) // Esperar antes de reintentar
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Error inesperado en polling")
                    updateNotification("Reconectando...")
                    delay(5000)
                }
            }
        }
    }

    /**
     * Procesa una actualización recibida de Telegram.
     */
    private suspend fun processUpdate(update: com.autogram.app.infrastructure.telegram.TelegramUpdate, conversation: Conversation) {
        val message = update.message ?: return

        // Ignorar mensajes que no son del chat configurado
        if (message.chat.id.toString() != conversation.targetChatId) {
            Timber.d("Mensaje de chat no configurado: ${message.chat.id}")
            return
        }

        Timber.d("Mensaje recibido de ${message.from?.first_name}: ${message.voice?.let { "voice(${it.duration}s)" } ?: message.text ?: "unknown"}")

        val settings = settingsRepository.get()

        // Descargar audio si es un mensaje de voz
        val audioFilePath = if (message.voice != null) {
            val audioDir = File(cacheDir, "audio_incoming").apply { mkdirs() }
            val downloadResult = telegramApiClient.downloadVoiceFile(message.voice.file_id, audioDir)
            if (downloadResult.isSuccess) {
                downloadResult.getOrThrow().absolutePath
            } else {
                Timber.e(downloadResult.exceptionOrNull(), "Error descargando audio")
                null
            }
        } else null

        // Crear mensaje en la base de datos
        val dbMessage = Message(
            id = UUID.randomUUID().toString(),
            conversationId = conversation.id,
            direction = MessageDirection.INCOMING,
            state = MessageState.RECEIVED,
            telegramMessageId = message.message_id,
            audioFilePath = audioFilePath,
            audioDurationMs = message.voice?.duration?.toLong()?.let { it * 1000 },
            textContent = message.text,
            createdAt = System.currentTimeMillis()
        )
        messageRepository.insert(dbMessage)

        // Actualizar timestamp de última actividad
        conversationRepository.upsert(conversation.copy(lastMessageAt = System.currentTimeMillis()))

        // Auto-reproducir si está configurado
        if (settings.autoPlayReplies && audioFilePath != null) {
            val audioFile = File(audioFilePath)
            playReplyUseCase.playVoiceMessage(dbMessage, callStateMonitor.isInCall.value)
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        Timber.i("Polling detenido")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Autogram Polling",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notificaciones del servicio de mensajería"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Autogram")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(statusText))
        } catch (_: Exception) {
        }
    }
}
