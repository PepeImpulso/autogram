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
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.MessageState
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.infrastructure.platform.CallStateMonitor
import com.autogram.app.infrastructure.telegram.mtproto.AuthState
import com.autogram.app.infrastructure.telegram.mtproto.TdLibClient
import com.autogram.app.ui.phone.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/**
 * Foreground service that keeps TDLib alive and pipes incoming voice notes
 * from Merlin into [PlayReplyUseCase] for automatic playback.
 *
 * Replaces the bot-API [AutogramPollingService]. We filter on chatId matching
 * the resolved @pepemaldo_bot DM and ignore outgoing messages (so we don't
 * try to play our own sent voice notes back).
 */
@AndroidEntryPoint
class MtprotoReceiverService : Service() {

    companion object {
        const val CHANNEL_ID = "autogram_mtproto_receiver"
        const val NOTIFICATION_ID = 2
        const val ACTION_START = "com.autogram.app.MTPROTO_START"
        const val ACTION_STOP = "com.autogram.app.MTPROTO_STOP"

        // Shared with SendVoiceMtprotoUseCase — same bot, same conversation.
        const val TARGET_USERNAME = "pepemaldo_bot"
        const val CONVERSATION_ID = "mtproto-default"

        @Volatile var isRunning = false
            private set
    }

    @Inject lateinit var tdLibClient: TdLibClient
    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var conversationRepository: ConversationRepository
    @Inject lateinit var playReplyUseCase: PlayReplyUseCase
    @Inject lateinit var callStateMonitor: CallStateMonitor

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var receiveJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        scope.cancel(); scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Conectando…"))
        callStateMonitor.start()
        isRunning = true
        Timber.i("MtprotoReceiverService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopReceiving()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startReceiving()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopReceiving()
        callStateMonitor.stop()
        scope.cancel()
        isRunning = false
        Timber.i("MtprotoReceiverService destroyed")
        super.onDestroy()
    }

    private fun startReceiving() {
        if (receiveJob?.isActive == true) return
        receiveJob = scope.launch {
            tdLibClient.ensureStarted()
            tdLibClient.authState.first { it is AuthState.Ready }
            val chatId = resolveMerlinChatId()
            upsertConversation(chatId)
            updateNotification("Escuchando respuestas de Merlin")
            Timber.i("Receiver subscribed to chatId=$chatId")

            tdLibClient.updates
                .filterIsInstance<TdApi.UpdateNewMessage>()
                .collect { update ->
                    val msg = update.message
                    if (msg.chatId != chatId || msg.isOutgoing) return@collect
                    when (val content = msg.content) {
                        is TdApi.MessageVoiceNote -> handleVoiceNote(msg, content)
                        // Text replies are ignored for now — they'll come
                        // back as part of a later TTS pass.
                        else -> Timber.d("Ignored non-voice incoming: ${content.javaClass.simpleName}")
                    }
                }
        }
    }

    private fun stopReceiving() {
        receiveJob?.cancel()
        receiveJob = null
    }

    private suspend fun resolveMerlinChatId(): Long {
        val res = tdLibClient.send(TdApi.SearchPublicChat(TARGET_USERNAME))
        if (res is TdApi.Error) {
            error("No se pudo resolver @$TARGET_USERNAME: ${res.code} ${res.message}")
        }
        return (res as TdApi.Chat).id
    }

    private suspend fun upsertConversation(chatId: Long) {
        val existing = conversationRepository.getById(CONVERSATION_ID)
        if (existing == null) {
            conversationRepository.upsert(
                Conversation(
                    id = CONVERSATION_ID,
                    targetChatId = chatId.toString(),
                    targetTitle = "Merlin",
                    botToken = "",
                    isActive = true
                )
            )
            conversationRepository.setActive(CONVERSATION_ID)
        }
    }

    private suspend fun handleVoiceNote(tdMsg: TdApi.Message, content: TdApi.MessageVoiceNote) {
        val voiceFile = content.voiceNote.voice
        val localPath = ensureDownloaded(voiceFile) ?: run {
            Timber.w("Skipping voice note ${tdMsg.id} — download failed")
            return
        }

        val message = Message(
            id = UUID.randomUUID().toString(),
            conversationId = CONVERSATION_ID,
            direction = MessageDirection.INCOMING,
            state = MessageState.RECEIVED,
            telegramMessageId = tdMsg.id,
            audioFilePath = localPath,
            audioDurationMs = content.voiceNote.duration * 1000L,
            createdAt = tdMsg.date.toLong() * 1000L
        )
        messageRepository.insert(message)
        playReplyUseCase.playVoiceMessage(
            message = message,
            isCallActive = callStateMonitor.isInCall.value
        )
    }

    /** Blocks until TDLib has the file on disk, or returns null on failure. */
    private suspend fun ensureDownloaded(file: TdApi.File): String? {
        if (file.local.isDownloadingCompleted && !file.local.path.isNullOrEmpty()) {
            return file.local.path
        }
        val result = tdLibClient.send(
            TdApi.DownloadFile().apply {
                fileId = file.id
                priority = 32
                offset = 0
                limit = 0
                synchronous = true
            }
        )
        return when (result) {
            is TdApi.File -> if (result.local.isDownloadingCompleted) result.local.path else null
            is TdApi.Error -> {
                Timber.w("DownloadFile failed: ${result.code} ${result.message}")
                null
            }
            else -> null
        }
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_polling),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
