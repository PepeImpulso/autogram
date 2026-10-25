package com.autogram.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.autogram.app.R
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.MessageState
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.domain.usecase.OutboxRetryUseCase
import com.autogram.app.domain.usecase.SummarizeMessageUseCase
import com.autogram.app.domain.usecase.TranscribeVoiceUseCase
import com.autogram.app.infrastructure.platform.CallStateMonitor
import com.autogram.app.infrastructure.platform.IncomingMessageNotifier
import com.autogram.app.infrastructure.privacy.AcceptableUseConsentStore
import com.autogram.app.infrastructure.telegram.mtproto.ApprovalPromptDetector
import com.autogram.app.infrastructure.telegram.mtproto.AuthState
import com.autogram.app.infrastructure.telegram.mtproto.TdLibClient
import com.autogram.app.ui.phone.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * Foreground service that keeps TDLib alive and pipes incoming text/audio
 * from every pinned target (chat + optional forum topic) into
 * [PlayReplyUseCase] for automatic playback.
 *
 * The pinned set is read reactively. Route snapshots are replaced atomically
 * while one buffered-message collector remains active, so editing circles does
 * not create a subscription gap. Sequential collection preserves arrival order
 * until a message is handed to the playback/transcription pipeline.
 */
@AndroidEntryPoint
class MtprotoReceiverService : Service() {

    companion object {
        const val CHANNEL_ID = "autogram_mtproto_receiver"
        const val NOTIFICATION_ID = 2
        const val ACTION_START = "com.autogram.app.MTPROTO_START"
        const val ACTION_STOP = "com.autogram.app.MTPROTO_STOP"
        private const val DOWNLOAD_MAX_ATTEMPTS = 3
        private const val DOWNLOAD_RETRY_BASE_DELAY_MS = 750L
        private const val PROCESS_MAX_ATTEMPTS = 3
        private const val PROCESS_RETRY_BASE_DELAY_MS = 250L

        @Volatile var isRunning = false
            private set
    }

    @Inject lateinit var tdLibClient: TdLibClient
    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var conversationRepository: ConversationRepository
    @Inject lateinit var playReplyUseCase: PlayReplyUseCase
    @Inject lateinit var outboxRetryUseCase: OutboxRetryUseCase
    @Inject lateinit var callStateMonitor: CallStateMonitor
    @Inject lateinit var incomingMessageNotifier: IncomingMessageNotifier
    @Inject lateinit var transcribeVoiceUseCase: TranscribeVoiceUseCase
    @Inject lateinit var summarizeMessageUseCase: SummarizeMessageUseCase
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var acceptableUseConsentStore: AcceptableUseConsentStore

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var supervisorJob: Job? = null
    private var newMessageCollectorJob: Job? = null
    private var callStateCollectorJob: Job? = null
    private var callEndDrainJob: Job? = null
    private val routingRef = AtomicReference<Map<ReceiverRouteKey, Conversation>>(emptyMap())
    private val processingMessageIds = ConcurrentHashMap.newKeySet<String>()
    // Audit-HIGH-6: guards against onCreate running after onDestroy on
    // Android's service lifecycle (rare but happens when the system rebinds).
    // Without the flag, the onCreate here would cancel the current scope and
    // spawn a fresh one that the already-run onDestroy never cleans up.
    @Volatile private var destroyed = false
    @Volatile private var holdsMicrophoneType = false
    @Volatile private var lastNotificationText = "Connecting…"

    // The merged manifest declares specialUse|mediaPlayback|microphone on this service.
    // Lint does not resolve the combined flavor manifest for these guarded
    // API-level branches and reports all three calls as missing a type.
    @SuppressLint("ForegroundServiceType")
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
        val notification = buildNotification("Connecting…")
        try {
            startForegroundWithTypes(notification)
        } catch (e: IllegalStateException) {
            // Android 12+ rejects FGS starts from background contexts (e.g.
            // Android Auto binding AutogramCarAppService while the app is
            // fully backgrounded — ForegroundServiceStartNotAllowedException
            // extends IllegalStateException). Bail gracefully — the next
            // foreground entry (MainActivity, AA session) retries.
            Timber.w(e, "FGS start denied; bailing")
            stopSelf()
            return
        }
        callStateMonitor.start()
        isRunning = true
        Timber.i("MtprotoReceiverService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!acceptableUseConsentStore.isAccepted()) {
            Timber.i("MtprotoReceiverService stopped until messaging terms are accepted")
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopSupervisor()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Started from a foreground screen after a background start:
                // upgrade the service so it also holds microphone access.
                if (!holdsMicrophoneType) {
                    runCatching { startForegroundWithTypes(buildNotification(lastNotificationText)) }
                        .onFailure { Timber.w(it, "FGS microphone upgrade failed") }
                }
                startSupervisor()
            }
        }
        return START_STICKY
    }

    /**
     * Starts (or re-types) the foreground service. The microphone type keeps
     * recording working while the phone is locked and Android Auto shows the
     * media screen (car tap-to-record); Android only grants it while the app
     * may use the mic, so a background start falls back to the base types
     * and onStartCommand upgrades later from a foreground entry.
     */
    @SuppressLint("ForegroundServiceType", "InlinedApi")
    private fun startForegroundWithTypes(notification: android.app.Notification) {
        val sdk = android.os.Build.VERSION.SDK_INT
        if (sdk < android.os.Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
            return
        }
        // API 29–33 only require explicit types for location, camera and
        // microphone; specialUse was introduced in API 34.
        val base = if (sdk >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        val micAllowed = sdk >= android.os.Build.VERSION_CODES.R &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (micAllowed) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    base or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
                holdsMicrophoneType = true
                return
            } catch (e: SecurityException) {
                Timber.i("FGS microphone type not allowed right now; starting without it")
            }
        }
        startForeground(NOTIFICATION_ID, notification, base)
        holdsMicrophoneType = false
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
     * Waits for TDLib auth, then observes the pinned conversation set. Routing
     * snapshots are replaced atomically while one incoming-message collector
     * remains alive, eliminating the cancellation gap that previously lost
     * messages whenever the user edited pinned circles.
     */
    private fun startSupervisor() {
        if (supervisorJob?.isActive == true) return
        supervisorJob = scope.launch {
            tdLibClient.ensureStarted()
            tdLibClient.authState.first { it is AuthState.Ready }
            startCallStateObserver()
            // WorkManager can run before TDLib finishes authenticating and
            // enter exponential backoff even though connectivity is already
            // available. Drain immediately on the Ready transition instead
            // of leaving queued messages behind until that stale timer fires.
            // OutboxRetryUseCase serializes this with any worker already in
            // flight, so the same message cannot be sent twice.
            when (outboxRetryUseCase.runOnce()) {
                OutboxRetryUseCase.Outcome.SUCCESS ->
                    Timber.i("Outbox drain after TDLib Ready completed")
                OutboxRetryUseCase.Outcome.RETRY ->
                    Timber.w("Outbox drain after TDLib Ready needs another retry")
            }

            conversationRepository.observePinned()
                .distinctUntilChanged()
                .collect { targets ->
                    val snapshot = buildReceiverRouting(targets)
                    routingRef.set(snapshot.routes)
                    snapshot.invalidTargets.forEach { target ->
                        Timber.w(
                            "Ignoring receiver target=${target.receiverLabel()} with invalid " +
                                "chatId=${target.targetChatId}"
                        )
                    }
                    snapshot.duplicateKeys.forEach { key ->
                        val duplicates = targets.filter {
                            it.targetChatId.toLongOrNull() == key.chatId && it.topicId == key.topicId
                        }
                        Timber.w(
                            "Duplicate receiver route chatId=${key.chatId} topicId=${key.topicId}; " +
                                "keeping first target=${duplicates.firstOrNull()?.receiverLabel()} " +
                            "and ignoring ${duplicates.drop(1).joinToString { it.receiverLabel() }}"
                        )
                    }
                    if (newMessageCollectorJob?.isActive != true) {
                        // Start exactly once, after the first routing snapshot.
                        // TdLibClient's channel retains messages that arrived
                        // during auth/outbox startup until this point.
                        newMessageCollectorJob = scope.launch { collectNewMessages() }
                    }
                    if (snapshot.routes.isEmpty()) {
                        updateNotification("Configura un destino en Ajustes")
                        Timber.i("No valid pinned conversation routes — receiver idle")
                        return@collect
                    }
                    updateListeningNotification(targets)
                    Timber.i(
                        "Receiver routing updated with ${snapshot.routes.size} exact route(s) " +
                            "for ${targets.joinToString { it.receiverLabel() }}"
                    )
                }
        }
    }

    private fun stopSupervisor() {
        newMessageCollectorJob?.cancel(); newMessageCollectorJob = null
        callStateCollectorJob?.cancel(); callStateCollectorJob = null
        callEndDrainJob?.cancel(); callEndDrainJob = null
        supervisorJob?.cancel(); supervisorJob = null
        routingRef.set(emptyMap())
    }

    private fun updateListeningNotification(targets: List<Conversation>) {
        updateNotification(
            if (targets.size == 1) {
                val t = targets.first()
                "Escuchando: ${t.targetTitle ?: "chat ${t.targetChatId}"}" +
                    (t.topicName?.let { " › $it" } ?: "")
            } else {
                "Escuchando ${targets.size} accesos rápidos"
            }
        )
    }

    private fun startCallStateObserver() {
        if (callStateCollectorJob?.isActive == true) return
        callStateCollectorJob = scope.launch {
            var wasInCall = callStateMonitor.isInCall.value
            callStateMonitor.isInCall
                .collect { isInCall ->
                    if (isInCall) {
                        // drainPendingIfAutoPlay includes a user-facing delay.
                        // Cancel that delay immediately if another call starts.
                        callEndDrainJob?.cancel()
                        callEndDrainJob = null
                    } else if (wasInCall) {
                        Timber.i("Phone call ended; resuming pending autoplay queue")
                        callEndDrainJob?.cancel()
                        callEndDrainJob = scope.launch {
                            if (callStateMonitor.isInCall.value) return@launch
                            try {
                                playReplyUseCase.drainPendingIfAutoPlay()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Timber.w(error, "Pending autoplay drain after call failed")
                            }
                        }
                    }
                    wasInCall = isInCall
                }
        }
    }

    private suspend fun collectNewMessages() {
        Timber.i("Receiver subscribed to the buffered incoming-message channel")
        tdLibClient.newMessages.collect { update ->
            try {
                processNewMessageWithRetry(update)
            } catch (cancelled: CancellationException) {
                val requeued = tdLibClient.requeueNewMessage(update)
                Timber.i(
                    "Receiver collector cancelled with in-flight chatId=${update.message.chatId} " +
                        "messageId=${update.message.id}; requeued=$requeued"
                )
                throw cancelled
            }
        }
    }

    private suspend fun processNewMessageWithRetry(update: TdApi.UpdateNewMessage) {
        for (attempt in 1..PROCESS_MAX_ATTEMPTS) {
            try {
                processNewMessage(update)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (attempt == PROCESS_MAX_ATTEMPTS) {
                    Timber.e(
                        error,
                        "Incoming Telegram message processing exhausted $PROCESS_MAX_ATTEMPTS " +
                            "attempts chatId=${update.message.chatId} messageId=${update.message.id}"
                    )
                    return
                }
                Timber.w(
                    error,
                    "Incoming Telegram message processing failed attempt=$attempt/" +
                        "$PROCESS_MAX_ATTEMPTS chatId=${update.message.chatId} " +
                        "messageId=${update.message.id}; retrying"
                )
                delay(PROCESS_RETRY_BASE_DELAY_MS * attempt)
            }
        }
    }

    private suspend fun processNewMessage(update: TdApi.UpdateNewMessage) {
        currentCoroutineContext().ensureActive()
        val msg = update.message
        val contentClass = msg.content.javaClass.simpleName
        if (msg.isOutgoing) {
            Timber.d(
                "Ignoring outgoing Telegram content=$contentClass " +
                    "chatId=${msg.chatId} messageId=${msg.id}"
            )
            return
        }
        val routeKey = receiverRouteKey(msg.chatId, msg.topicId)
        if (routeKey == null) {
            Timber.w(
                "Unmatched Telegram content=$contentClass chatId=${msg.chatId} " +
                    "unsupportedTopic=${msg.topicId?.javaClass?.simpleName} messageId=${msg.id}"
            )
            return
        }
        val routing = routingRef.get()
        val target = routing[routeKey]
        // stopSupervisor cancels the collector before clearing the route map.
        // Recheck cancellation here so that race requeues instead of treating
        // the in-flight update as an unmatched message.
        currentCoroutineContext().ensureActive()
        if (target == null) {
            Timber.w(
                "Unmatched Telegram route chatId=${routeKey.chatId} topicId=${routeKey.topicId} " +
                    "content=$contentClass messageId=${msg.id} " +
                    "configuredRoutes=${routing.keys.joinToString()}"
            )
            return
        }
        val localMessageId = receiverMessageId(msg.chatId, msg.id)
        if (!processingMessageIds.add(localMessageId)) {
            Timber.i(
                "Ignoring duplicate in-flight Telegram update chatId=${msg.chatId} messageId=${msg.id}"
            )
            return
        }
        try {
            if (messageRepository.getById(localMessageId) != null) {
                Timber.i(
                    "Ignoring already-persisted Telegram update chatId=${msg.chatId} " +
                        "messageId=${msg.id} localMessageId=$localMessageId"
                )
                return
            }
            Timber.i(
                "Incoming Telegram content=$contentClass target=${target.receiverLabel()} " +
                    "chatId=${routeKey.chatId} topicId=${routeKey.topicId} messageId=${msg.id}"
            )
            when (val content = classifyReceiverContent(msg.content)) {
                is ReceiverContent.Audio ->
                    handleAudio(target, msg, localMessageId, contentClass, content)
                is ReceiverContent.Text -> handleText(target, msg, localMessageId, content.text)
                is ReceiverContent.Unsupported -> Timber.w(
                    "Unsupported incoming Telegram content=${content.className} " +
                        "target=${target.receiverLabel()} messageId=${msg.id}"
                )
            }
        } finally {
            processingMessageIds.remove(localMessageId)
        }
    }

    private suspend fun handleAudio(
        target: Conversation,
        tdMsg: TdApi.Message,
        localMessageId: String,
        contentClass: String,
        content: ReceiverContent.Audio
    ) {
        val localPath = ensureDownloaded(
            file = content.file,
            targetLabel = target.receiverLabel(),
            contentClass = contentClass,
            telegramMessageId = tdMsg.id
        ) ?: run {
            Timber.w(
                "Skipping incoming audio content=$contentClass target=${target.receiverLabel()} " +
                    "messageId=${tdMsg.id} after bounded download retries"
            )
            return
        }

        val message = Message(
            id = localMessageId,
            conversationId = target.id,
            direction = MessageDirection.INCOMING,
            state = MessageState.RECEIVED,
            telegramMessageId = tdMsg.id,
            audioFilePath = localPath,
            audioDurationMs = content.durationSeconds.coerceAtLeast(0).toLong() * 1000L,
            createdAt = tdMsg.date.toLong() * 1000L
        )
        messageRepository.insert(message)
        // Deferred auto-delete: this new voice becomes the "last original"
        // on this conversation, so any previously-played voice here is
        // eligible for cleanup (if the user opted into auto-delete).
        // Moved out of PlayReplyUseCase so the LATEST message is always
        // preserved for the HeroHeader replay button — files are only
        // dropped once a newer message supersedes them.
        val settings = settingsRepository.get()
        if (settings.autoDeletePlayed) {
            runCatching { cleanupPriorPlayedAudio(target.id, keepMessageId = message.id) }
                .onFailure { Timber.w(it, "Deferred autoDelete cleanup failed") }
        }
        // Surface a phone notification with listen, reply, and mark-as-read
        // actions alongside the richer Android Auto template UI.
        incomingMessageNotifier.postVoiceReceived(
            conversation = target,
            timestampMs = message.createdAt,
            messageId = message.id,
            // IncomingMessageNotifier currently labels attached media as
            // audio/ogg. Only expose compatible OGG files there; MP3/M4A
            // attachments still play through AudioPlayer without being
            // misdeclared to Android Auto's notification reader.
            audioFilePath = localPath.takeIf { content.oggTranscriptionCompatible }
        )
        val inCall = callStateMonitor.isInCall.value
        Timber.i(
            "Incoming audio ${message.id} content=$contentClass target=${target.receiverLabel()} " +
            "mime=${content.mimeType.ifBlank { "unknown" }} " +
            "oggCompatible=${content.oggTranscriptionCompatible} autoPlay=${settings.autoPlayReplies} " +
            "inCall=$inCall " +
            "summarize=${settings.summarizeLongMessages} engine=${settings.summaryEngine}"
        )
        if (content.oggTranscriptionCompatible &&
            settings.summarizeLongMessages &&
            settings.summaryEngine != com.autogram.app.domain.model.SummaryEngineType.NONE
        ) {
            // Summary mode — skip the original audio. Transcribe (which
            // chains summarize internally), then TTS the summary or, for
            // short messages that didn't qualify, the raw transcription.
            // The user has a "play original" button on Main if they want
            // the full audio.
            scope.launch {
                transcribeVoiceUseCase.transcribe(message.id)
                val updated = messageRepository.getById(message.id) ?: return@launch
                playReplyUseCase.playSummaryOrTranscription(
                    message = updated,
                    isCallActive = callStateMonitor.isInCall.value
                )
            }
        } else {
            // Classic mode: play the audio now, transcribe + summarize in
            // the background for display in History when the file is an OGG
            // container supported by the transcription pipeline.
            if (content.oggTranscriptionCompatible) {
                scope.launch { transcribeVoiceUseCase.transcribe(message.id) }
            }
            playReplyUseCase.playVoiceMessage(
                message = message,
                isCallActive = callStateMonitor.isInCall.value
            )
        }
    }

    private suspend fun handleText(
        target: Conversation,
        tdMsg: TdApi.Message,
        localMessageId: String,
        text: String
    ) {
        if (text.isBlank()) return
        // Hermes exec-approval request (issue #87): speak only a short pointer
        // to Telegram. The original text is stored untouched in textContent;
        // the notice goes in spokenText and is what TTS and the car surfaces
        // read. See ApprovalPromptDetector for the signals and why.
        val approvalNotice = approvalNoticeFor(tdMsg, text)
        val message = Message(
            id = localMessageId,
            conversationId = target.id,
            direction = MessageDirection.INCOMING,
            state = MessageState.RECEIVED,
            telegramMessageId = tdMsg.id,
            textContent = text,
            createdAt = tdMsg.date.toLong() * 1000L,
            spokenText = approvalNotice
        )
        messageRepository.insert(message)
        incomingMessageNotifier.postTextReceived(
            conversation = target,
            preview = approvalNotice ?: text,
            timestampMs = message.createdAt,
            messageId = message.id,
            isApprovalNotice = approvalNotice != null
        )
        // Never summarize an approval request: the summary would send the
        // command to the configured LLM endpoint and could replace the notice.
        if (approvalNotice == null) {
            scope.launch { summarizeMessageUseCase.summarize(message.id) }
        }
        playReplyUseCase.playTextMessage(
            message = message,
            isCallActive = callStateMonitor.isInCall.value
        )
    }

    /**
     * Returns the localized short notice to speak instead of [text] when this
     * message is a Hermes exec-approval request from a bot on a configured
     * route, otherwise null (the message is read normally). Fails open: any
     * lookup problem means the message is treated as a normal one.
     */
    private suspend fun approvalNoticeFor(tdMsg: TdApi.Message, text: String): String? {
        val keyboardSignal = ApprovalPromptDetector.hasApprovalKeyboard(tdMsg.replyMarkup)
        if (!keyboardSignal && !ApprovalPromptDetector.isTextFallback(text)) return null
        val senderId = ApprovalPromptDetector.candidateSenderUserId(tdMsg) ?: return null
        val lookup = try {
            tdLibClient.send(TdApi.GetUser(senderId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "Sender lookup failed for approval-shaped message ${tdMsg.id}")
            null
        }
        val sender = lookup as? TdApi.User
        // A forged ea: keyboard only changes what is spoken (the notice is
        // harmless), so when the lookup itself failed the keyboard signal alone
        // is enough. A resolved non-bot sender is always rejected, and the
        // plain-text signal always requires the bot check.
        val trusted = if (sender != null) sender.type is TdApi.UserTypeBot else keyboardSignal
        if (!trusted) {
            Timber.i("Approval-shaped message ${tdMsg.id} is not from a bot; reading it normally")
            return null
        }
        val languageTag = runCatching { settingsRepository.get().languageTag }.getOrDefault("")
        val localized = if (languageTag.isBlank()) {
            this
        } else {
            createConfigurationContext(
                Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(languageTag)) }
            )
        }
        // Log only the message id: the command text must not reach logs/Sentry.
        Timber.i("Approval request ${tdMsg.id} will be spoken as a short Telegram pointer")
        return localized.getString(R.string.approval_request_spoken_notice)
    }

    /**
     * Deferred auto-delete: drops the file + nulls audioFilePath on any
     * INCOMING voice messages on [conversationId] that have already been
     * played and whose arrival predates [keepMessageId]. The new message
     * becomes the single preserved "last original" on the conversation.
     *
     * We skip anything not yet played (queued, pending, failed) — those
     * files are still the best-available representation for that row and
     * may still be needed. Only already-heard audios are safe to drop.
     */
    private suspend fun cleanupPriorPlayedAudio(conversationId: String, keepMessageId: String) {
        val all = messageRepository.getAll()
        for (m in all) {
            if (m.conversationId != conversationId) continue
            if (m.id == keepMessageId) continue
            if (m.direction != com.autogram.app.domain.model.MessageDirection.INCOMING) continue
            if (m.state != com.autogram.app.domain.model.MessageState.PLAYED) continue
            val path = m.audioFilePath
            if (path.isNullOrBlank()) continue
            runCatching { java.io.File(path).delete() }
            messageRepository.update(m.copy(audioFilePath = null))
        }
    }

    /**
     * Blocks until TDLib has the file on disk. Transient failures are retried
     * in place so FIFO reception order is preserved; permanent failures stop
     * immediately. The buffered TDLib channel holds later messages while this
     * bounded retry runs.
     */
    private suspend fun ensureDownloaded(
        file: TdApi.File,
        targetLabel: String,
        contentClass: String,
        telegramMessageId: Long
    ): String? {
        file.completedLocalPath()?.let { return it }

        for (attempt in 1..DOWNLOAD_MAX_ATTEMPTS) {
            val result = tdLibClient.send(
                TdApi.DownloadFile().apply {
                    fileId = file.id
                    priority = 32
                    offset = 0
                    limit = 0
                    synchronous = true
                }
            )
            when (result) {
                is TdApi.File -> {
                    result.completedLocalPath()?.let { return it }
                    Timber.w(
                        "Download incomplete attempt=$attempt/$DOWNLOAD_MAX_ATTEMPTS " +
                            "content=$contentClass target=$targetLabel messageId=$telegramMessageId"
                    )
                }
                is TdApi.Error -> {
                    val transient = isTransientDownloadError(result.code, result.message)
                    Timber.w(
                        "Download failed attempt=$attempt/$DOWNLOAD_MAX_ATTEMPTS transient=$transient " +
                            "code=${result.code} content=$contentClass target=$targetLabel " +
                            "messageId=$telegramMessageId error=${result.message}"
                    )
                    if (!transient) return null
                }
                else -> Timber.w(
                    "Unexpected download result=${result.javaClass.simpleName} " +
                        "attempt=$attempt/$DOWNLOAD_MAX_ATTEMPTS content=$contentClass " +
                        "target=$targetLabel messageId=$telegramMessageId"
                )
            }
            if (attempt < DOWNLOAD_MAX_ATTEMPTS) {
                delay(DOWNLOAD_RETRY_BASE_DELAY_MS * attempt)
            }
        }
        Timber.w(
            "Download exhausted $DOWNLOAD_MAX_ATTEMPTS attempts content=$contentClass " +
                "target=$targetLabel messageId=$telegramMessageId"
        )
        return null
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
        lastNotificationText = text
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}

internal data class ReceiverRouteKey(val chatId: Long, val topicId: Long?)

/** Stable local identity used to make repeated TDLib updates idempotent. */
internal fun receiverMessageId(chatId: Long, telegramMessageId: Long): String =
    "telegram:$chatId:$telegramMessageId"

internal data class ReceiverRoutingSnapshot(
    val routes: Map<ReceiverRouteKey, Conversation>,
    val duplicateKeys: Set<ReceiverRouteKey>,
    val invalidTargets: List<Conversation>
)

/** Builds an immutable routing snapshot while preserving pinned-list order. */
internal fun buildReceiverRouting(targets: List<Conversation>): ReceiverRoutingSnapshot {
    val routes = linkedMapOf<ReceiverRouteKey, Conversation>()
    val duplicates = linkedSetOf<ReceiverRouteKey>()
    val invalid = mutableListOf<Conversation>()
    for (target in targets) {
        val chatId = target.targetChatId.toLongOrNull()
        if (chatId == null) {
            invalid += target
            continue
        }
        val key = ReceiverRouteKey(chatId, target.topicId)
        if (routes.putIfAbsent(key, target) != null) duplicates += key
    }
    return ReceiverRoutingSnapshot(routes.toMap(), duplicates.toSet(), invalid.toList())
}

/** Returns null for topic kinds Autogram cannot configure or route exactly. */
internal fun receiverRouteKey(chatId: Long, topic: TdApi.MessageTopic?): ReceiverRouteKey? =
    when (topic) {
        null -> ReceiverRouteKey(chatId, null)
        is TdApi.MessageTopicForum -> ReceiverRouteKey(chatId, topic.forumTopicId.toLong())
        else -> null
    }

internal sealed interface ReceiverContent {
    data class Text(val text: String) : ReceiverContent

    data class Audio(
        val file: TdApi.File,
        val durationSeconds: Int,
        val mimeType: String,
        val fileName: String?,
        val oggTranscriptionCompatible: Boolean
    ) : ReceiverContent

    data class Unsupported(val className: String) : ReceiverContent
}

/** Normalizes Telegram's three audio representations into one playback path. */
internal fun classifyReceiverContent(content: TdApi.MessageContent): ReceiverContent = when (content) {
    is TdApi.MessageText -> ReceiverContent.Text(content.text?.text.orEmpty())
    is TdApi.MessageVoiceNote -> {
        val note = content.voiceNote
        val file = note?.voice
        if (note == null || file == null) {
            ReceiverContent.Unsupported(content.javaClass.simpleName)
        } else {
            ReceiverContent.Audio(
                file = file,
                durationSeconds = note.duration,
                mimeType = note.mimeType.orEmpty(),
                fileName = null,
                // Older TDLib rows can omit MIME for genuine voice notes;
                // Telegram voice notes were OGG/Opus in those app versions.
                oggTranscriptionCompatible = isOggAudio(
                    mimeType = note.mimeType,
                    fileName = null,
                    assumeOggWhenUnknown = true
                )
            )
        }
    }
    is TdApi.MessageAudio -> {
        val audio = content.audio
        val file = audio?.audio
        if (audio == null || file == null) {
            ReceiverContent.Unsupported(content.javaClass.simpleName)
        } else {
            ReceiverContent.Audio(
                file = file,
                durationSeconds = audio.duration,
                mimeType = audio.mimeType.orEmpty(),
                fileName = audio.fileName,
                oggTranscriptionCompatible = isOggAudio(audio.mimeType, audio.fileName)
            )
        }
    }
    is TdApi.MessageDocument -> {
        val document = content.document
        val mimeType = document?.mimeType.orEmpty()
        val fileName = document?.fileName
        val file = document?.document
        if (document == null || file == null || !isAudioDocument(mimeType, fileName)) {
            ReceiverContent.Unsupported(content.javaClass.simpleName)
        } else {
            ReceiverContent.Audio(
                file = file,
                durationSeconds = 0,
                mimeType = mimeType,
                fileName = fileName,
                oggTranscriptionCompatible = isOggAudio(mimeType, fileName)
            )
        }
    }
    else -> ReceiverContent.Unsupported(content.javaClass.simpleName)
}

internal fun isOggAudio(
    mimeType: String?,
    fileName: String?,
    assumeOggWhenUnknown: Boolean = false
): Boolean {
    val normalizedMime = mimeType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
    val normalizedName = fileName.orEmpty().trim().lowercase(Locale.ROOT)
    if (normalizedMime == "audio/ogg" || normalizedMime == "application/ogg") return true
    if (normalizedName.endsWith(".ogg") || normalizedName.endsWith(".oga")) return true
    return assumeOggWhenUnknown && normalizedMime.isBlank() && normalizedName.isBlank()
}

/** Accept audio documents whose sender omitted or generalized the MIME type. */
internal fun isAudioDocument(mimeType: String?, fileName: String?): Boolean {
    val normalizedMime = mimeType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
    if (normalizedMime.startsWith("audio/")) return true
    val normalizedName = fileName.orEmpty().trim().lowercase(Locale.ROOT)
    return AUDIO_DOCUMENT_EXTENSIONS.any(normalizedName::endsWith)
}

private val AUDIO_DOCUMENT_EXTENSIONS = setOf(
    ".aac", ".flac", ".m4a", ".mp3", ".oga", ".ogg", ".opus", ".wav", ".webm"
)

internal fun isTransientDownloadError(code: Int, message: String): Boolean {
    if (code == 408 || code == 429 || code == 598 || code in 500..599) return true
    val normalized = message.uppercase(Locale.ROOT)
    return normalized.contains("TIMEOUT") ||
        normalized.contains("NETWORK") ||
        normalized.contains("CONNECTION") ||
        normalized.contains("TEMPORAR") ||
        normalized.contains("FLOOD_WAIT")
}

private fun TdApi.File.completedLocalPath(): String? {
    val localFile = local ?: return null
    val path = localFile.path?.takeIf { localFile.isDownloadingCompleted && it.isNotBlank() } ?: return null
    return path.takeIf { java.io.File(it).isFile }
}

// Logged at INFO/WARN, which become Sentry breadcrumbs: keep chat titles and
// topic names out of it and identify the target by its local id only.
private fun Conversation.receiverLabel(): String = buildString {
    append("conversation:$id")
    if (!topicName.isNullOrBlank()) append(" (topic)")
}
