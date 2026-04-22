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
import com.autogram.app.domain.usecase.SummarizeMessageUseCase
import com.autogram.app.domain.usecase.TranscribeVoiceUseCase
import com.autogram.app.infrastructure.platform.CallStateMonitor
import com.autogram.app.infrastructure.platform.IncomingMessageNotifier
import com.autogram.app.infrastructure.telegram.mtproto.AuthState
import com.autogram.app.infrastructure.telegram.mtproto.TdLibClient
import com.autogram.app.ui.phone.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/**
 * Foreground service that keeps TDLib alive and pipes incoming voice notes
 * from every pinned target (chat + optional forum topic) into
 * [PlayReplyUseCase] for automatic playback.
 *
 * The pinned set is read reactively — when the user adds/removes a circle the
 * collector is re-bound with the new routing table. A single collector handles
 * all targets so playback queueing in PlayReplyUseCase stays strictly
 * sequential even when several circles receive at the same time.
 */
@AndroidEntryPoint
class MtprotoReceiverService : Service() {

    companion object {
        const val CHANNEL_ID = "autogram_mtproto_receiver"
        const val NOTIFICATION_ID = 2
        const val ACTION_START = "com.autogram.app.MTPROTO_START"
        const val ACTION_STOP = "com.autogram.app.MTPROTO_STOP"

        @Volatile var isRunning = false
            private set
    }

    @Inject lateinit var tdLibClient: TdLibClient
    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var conversationRepository: ConversationRepository
    @Inject lateinit var playReplyUseCase: PlayReplyUseCase
    @Inject lateinit var callStateMonitor: CallStateMonitor
    @Inject lateinit var incomingMessageNotifier: IncomingMessageNotifier
    @Inject lateinit var transcribeVoiceUseCase: TranscribeVoiceUseCase
    @Inject lateinit var summarizeMessageUseCase: SummarizeMessageUseCase

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var supervisorJob: Job? = null
    private var activeCollectorJob: Job? = null
    // Audit-HIGH-6: guards against onCreate running after onDestroy on
    // Android's service lifecycle (rare but happens when the system rebinds).
    // Without the flag, the onCreate here would cancel the current scope and
    // spawn a fresh one that the already-run onDestroy never cleans up.
    @Volatile private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        // Don't blindly cancel+recreate: if we're already alive (rebind),
        // reuse the scope. Only allocate a fresh one when it was previously
        // cancelled.
        val job = scope.coroutineContext[Job]
        if (job == null || !job.isActive) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Conectando…"))
        callStateMonitor.start()
        isRunning = true
        Timber.i("MtprotoReceiverService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSupervisor()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startSupervisor()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (destroyed) { super.onDestroy(); return }
        destroyed = true
        stopSupervisor()
        runCatching { callStateMonitor.stop() }
        scope.cancel()
        isRunning = false
        Timber.i("MtprotoReceiverService destroyed")
        super.onDestroy()
    }

    /**
     * Waits for TDLib auth, then observes the pinned conversation set and
     * (re)starts a single update collector whose routing table reflects the
     * current pins — added when the user creates a circle, removed when they
     * delete one.
     */
    private fun startSupervisor() {
        if (supervisorJob?.isActive == true) return
        supervisorJob = scope.launch {
            tdLibClient.ensureStarted()
            tdLibClient.authState.first { it is AuthState.Ready }

            conversationRepository.observePinned()
                .distinctUntilChanged { old, new ->
                    // Ignore title / photo changes — only re-bind when the set of
                    // (chatId, topicId) routing keys actually changes.
                    old.map { it.targetChatId to it.topicId }.toSet() ==
                        new.map { it.targetChatId to it.topicId }.toSet()
                }
                .collect { targets ->
                    activeCollectorJob?.cancel()
                    if (targets.isEmpty()) {
                        updateNotification("Configura un destino en Ajustes")
                        Timber.i("No pinned conversations — receiver idle")
                        return@collect
                    }
                    activeCollectorJob = scope.launch { collectFor(targets) }
                }
        }
    }

    private fun stopSupervisor() {
        activeCollectorJob?.cancel(); activeCollectorJob = null
        supervisorJob?.cancel(); supervisorJob = null
    }

    private suspend fun collectFor(targets: List<Conversation>) {
        // Routing table: (chatId, topicId) → Conversation. topicId=null keys
        // match messages that arrive on a non-forum chat / forum General.
        val routing: Map<Pair<Long, Long?>, Conversation> = targets.associateBy {
            it.targetChatId.toLong() to it.topicId
        }
        updateNotification(
            if (targets.size == 1) {
                val t = targets.first()
                "Escuchando: ${t.targetTitle ?: "chat ${t.targetChatId}"}" +
                    (t.topicName?.let { " › $it" } ?: "")
            } else {
                "Escuchando ${targets.size} accesos rápidos"
            }
        )
        Timber.i("Receiver subscribed to ${targets.size} target(s)")

        tdLibClient.updates
            .filterIsInstance<TdApi.UpdateNewMessage>()
            .collect { update ->
                val msg = update.message
                if (msg.isOutgoing) return@collect
                val topicId = (msg.topicId as? TdApi.MessageTopicForum)
                    ?.forumTopicId?.toLong()
                val target = routing[msg.chatId to topicId] ?: return@collect
                when (val content = msg.content) {
                    is TdApi.MessageVoiceNote -> handleVoiceNote(target, msg, content)
                    is TdApi.MessageText -> handleText(target, msg, content)
                    else -> Timber.d("Ignored non-voice incoming: ${content.javaClass.simpleName}")
                }
            }
    }

    private suspend fun handleVoiceNote(
        target: Conversation,
        tdMsg: TdApi.Message,
        content: TdApi.MessageVoiceNote
    ) {
        val voiceFile = content.voiceNote.voice
        val localPath = ensureDownloaded(voiceFile) ?: run {
            Timber.w("Skipping voice note ${tdMsg.id} — download failed")
            return
        }

        val message = Message(
            id = UUID.randomUUID().toString(),
            conversationId = target.id,
            direction = MessageDirection.INCOMING,
            state = MessageState.RECEIVED,
            telegramMessageId = tdMsg.id,
            audioFilePath = localPath,
            audioDurationMs = content.voiceNote.duration * 1000L,
            createdAt = tdMsg.date.toLong() * 1000L
        )
        messageRepository.insert(message)
        // Surface a MessagingStyle+CarAppExtender notification so Android Auto
        // shows the message on the head unit and lets the user tap Listen/Reply.
        incomingMessageNotifier.postVoiceReceived(
            conversation = target,
            timestampMs = message.createdAt,
            messageId = message.id
        )
        // Kick off transcription in the service's own scope so playback and
        // recognition run in parallel. The use case no-ops if the feature
        // toggle is off, so it's safe to always call.
        scope.launch { transcribeVoiceUseCase.transcribe(message.id) }
        playReplyUseCase.playVoiceMessage(
            message = message,
            isCallActive = callStateMonitor.isInCall.value
        )
    }

    private suspend fun handleText(
        target: Conversation,
        tdMsg: TdApi.Message,
        content: TdApi.MessageText
    ) {
        val text = content.text.text.orEmpty()
        if (text.isBlank()) return
        val message = Message(
            id = UUID.randomUUID().toString(),
            conversationId = target.id,
            direction = MessageDirection.INCOMING,
            state = MessageState.RECEIVED,
            telegramMessageId = tdMsg.id,
            textContent = text,
            createdAt = tdMsg.date.toLong() * 1000L
        )
        messageRepository.insert(message)
        incomingMessageNotifier.postTextReceived(
            conversation = target,
            preview = text,
            timestampMs = message.createdAt,
            messageId = message.id
        )
        scope.launch { summarizeMessageUseCase.summarize(message.id) }
        playReplyUseCase.playTextMessage(
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
