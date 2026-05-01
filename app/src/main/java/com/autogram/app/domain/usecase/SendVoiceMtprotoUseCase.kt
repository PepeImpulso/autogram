package com.autogram.app.domain.usecase

import android.content.Context
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.MessageState
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.infrastructure.audio.AudioPlayer
import com.autogram.app.infrastructure.audio.AudioRecorder
import com.autogram.app.infrastructure.audio.EmptyMessageFilter
import com.autogram.app.infrastructure.audio.HapticCues
import com.autogram.app.infrastructure.audio.RecordingSounds
import com.autogram.app.infrastructure.platform.ConnectivityObserver
import com.autogram.app.infrastructure.retry.RetryWorker
import com.autogram.app.infrastructure.telegram.mtproto.AuthState
import com.autogram.app.infrastructure.telegram.mtproto.TdLibSender
import com.autogram.app.infrastructure.telegram.mtproto.toForumTopicOrNull
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 2b: voice (and text) messages sent via MTProto to whichever chat +
 * optional forum topic the user selected in the target picker.
 */
@Singleton
class SendVoiceMtprotoUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tdLibClient: TdLibSender,
    private val audioRecorder: AudioRecorder,
    private val emptyMessageFilter: EmptyMessageFilter,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val playReplyUseCase: PlayReplyUseCase,
    private val recordingSounds: RecordingSounds,
    private val hapticCues: HapticCues,
    private val connectivityObserver: ConnectivityObserver,
    private val settingsRepository: SettingsRepository,
    private val audioPlayer: AudioPlayer
) {

    sealed class SendStatus {
        object Idle : SendStatus()
        object Recording : SendStatus()
        object Validating : SendStatus()
        object Sending : SendStatus()
        data class Sent(val telegramMessageId: Long) : SendStatus()
        // The user's message is saved — we couldn't reach Telegram (offline / not
        // authenticated) so it'll be retried by RetryWorker when conditions allow.
        object QueuedOffline : SendStatus()
        // Preview-before-send (toggle in Settings). Holds the file + target so
        // the UI can offer play / retry / send, and so confirmPreview() can
        // resume the same send path. File stays on disk until confirm or cancel.
        data class PreviewReady(val durationMs: Long) : SendStatus()
        object PreviewPlaying : SendStatus()
        data class Error(val message: String) : SendStatus()
        data class Discarded(val reason: String) : SendStatus()
    }

    private val _status = MutableStateFlow<SendStatus>(SendStatus.Idle)
    val status: StateFlow<SendStatus> = _status.asStateFlow()

    // Id of the conversation that is currently recording/sending, so the UI can
    // highlight the right circle when several targets are pinned.
    private val _activeTargetId = MutableStateFlow<String?>(null)
    val activeTargetId: StateFlow<String?> = _activeTargetId.asStateFlow()

    @Volatile private var currentAudioFile: File? = null
    @Volatile private var currentTargetId: String? = null
    // Preview-mode state: kept alive between stopAndSend() and
    // confirmPreview()/cancelPreview() so the UI can play it back and retry.
    @Volatile private var previewFile: File? = null
    @Volatile private var previewTargetId: String? = null
    @Volatile private var previewDurationMs: Long = 0L

    suspend fun startRecording(targetConversationId: String? = null): Result<Unit> {
        if (audioRecorder.isRecording()) {
            return Result.failure(IllegalStateException("Ya hay una grabación en curso"))
        }
        // Reject starts when a previous send is mid-flight. Without
        // this guard, a user double-tap while the pipeline is
        // Validating / Sending / PreviewReady starts a new recording
        // that overwrites `currentAudioFile`, flipping the UI out of
        // the Sending state and potentially shipping a corrupt send.
        // Allow starts from terminal states (Idle / Sent / Error /
        // Discarded / QueuedOffline) — the previous send is done.
        when (_status.value) {
            SendStatus.Idle,
            is SendStatus.Sent,
            is SendStatus.Error,
            is SendStatus.Discarded,
            SendStatus.QueuedOffline -> Unit
            else -> {
                Timber.w("startRecording refused — pipeline busy in state ${_status.value}")
                return Result.failure(IllegalStateException("Pipeline ocupado: ${_status.value::class.simpleName}"))
            }
        }
        // Interrupt any voice reply currently playing so the driver can speak
        // over an unwanted/long-winded answer from Merlin and send their own
        // prompt. v1.20.11: stopAll no longer wipes the pending queue — the
        // queued messages survive so the user can drain them after sending
        // their reply (via HeroHeader's "Play N pending" button).
        playReplyUseCase.stopAll()
        val audioDir = File(context.cacheDir, "audio_outgoing").apply { mkdirs() }
        val audioFile = File(audioDir, "${UUID.randomUUID()}.ogg")
        val result = audioRecorder.start(audioFile)
        return if (result.isSuccess) {
            currentAudioFile = audioFile
            currentTargetId = targetConversationId
            _activeTargetId.value = targetConversationId
            _status.value = SendStatus.Recording
            recordingSounds.playStart()
            hapticCues.playStart()
            Result.success(Unit)
        } else {
            val err = result.exceptionOrNull()?.message ?: "Error al iniciar grabación"
            _status.value = SendStatus.Error(err)
            Result.failure(result.exceptionOrNull() ?: Exception(err))
        }
    }

    suspend fun cancelRecording() {
        val audioFile = audioRecorder.stop()
        audioFile?.delete()
        currentAudioFile = null
        currentTargetId = null
        _activeTargetId.value = null
        _status.value = SendStatus.Idle
        recordingSounds.playEnd()
        hapticCues.playEnd()
        // v1.22.0 — recording ended; resume any messages that were
        // deferred to the queue while the mic was hot. No-op if the
        // user has auto-play off (queue stays parked for manual drain).
        playReplyUseCase.drainPendingIfAutoPlay()
    }

    /**
     * v1.23.0-rc.3 — stop the in-flight recording but DON'T send.
     * Returns the audio file for the caller to inspect (transcribe,
     * parse, etc.) and decide what to do with it. Caller is responsible
     * for either dispatching the file via [sendVoiceShortcut] or
     * deleting it. Used by the assistant's voice-command path so the
     * recorded audio can be transcribed locally before deciding
     * whether to send it as a voice note or treat it as a control
     * command.
     */
    suspend fun stopWithoutSending(): File? {
        val audioFile = audioRecorder.stop()
        currentAudioFile = null
        currentTargetId = null
        _activeTargetId.value = null
        _status.value = SendStatus.Idle
        recordingSounds.playEnd()
        hapticCues.playEnd()
        playReplyUseCase.drainPendingIfAutoPlay()
        return audioFile
    }

    /**
     * @param skipPreview If true, the caller's `previewBeforeSend` setting is
     * bypassed and the recording goes straight to the send path. AA passes
     * true because NavigationTemplate has no preview sheet — honoring the
     * setting there would silently park the file with no UI to confirm /
     * cancel, and the user would think their voice note was sent when it
     * wasn't. Phone UI passes the default (false) so the phone preview
     * sheet still appears when the user has it enabled.
     */
    suspend fun stopAndSend(skipPreview: Boolean = false): Result<Long> {
        val result = stopAndSendInner(skipPreview)
        // v1.22.0 — recorder is stopped (or never started) by the time we
        // get here. If the parked file is in PreviewReady state the user
        // will resolve it via confirmPreview / cancelPreview which each
        // drain themselves; for every other terminal state (Sent /
        // Error / Discarded / QueuedOffline / no-recording) the queue
        // can resume now. drainPendingIfAutoPlay is a no-op when nothing
        // is queued, autoplay is off, or the audio path is busy.
        if (_status.value !is SendStatus.PreviewReady) {
            playReplyUseCase.drainPendingIfAutoPlay()
        }
        return result
    }

    private suspend fun stopAndSendInner(skipPreview: Boolean): Result<Long> {
        val startedFile = currentAudioFile
            ?: return Result.failure(IllegalStateException("No hay grabación en curso"))
        val targetId = currentTargetId

        val audioFile = audioRecorder.stop()
        currentAudioFile = null
        recordingSounds.playEnd()
        hapticCues.playEnd()
        if (audioFile == null || !audioFile.exists()) {
            _activeTargetId.value = null
            _status.value = SendStatus.Error("No se pudo guardar el audio")
            return Result.failure(Exception("No se pudo guardar el audio"))
        }

        _status.value = SendStatus.Validating
        val filter = emptyMessageFilter.validate(audioFile, minDurationMs = 500)
        if (!filter.accepted) {
            audioFile.delete()
            _activeTargetId.value = null
            _status.value = SendStatus.Discarded(filter.reason ?: "Mensaje vacío")
            return Result.failure(Exception("Descartado: ${filter.reason}"))
        }

        val target = resolveTarget(targetId)
        if (target == null) {
            _activeTargetId.value = null
            _status.value = SendStatus.Error("Configura un destino en Ajustes")
            return Result.failure(IllegalStateException("No active conversation"))
        }

        val durationMs = emptyMessageFilter.getDurationMs(audioFile) ?: 0L

        // Preview-before-send: park the file + target, let the UI drive the
        // decision. confirmPreview() picks up exactly where we left off;
        // cancelPreview() discards. Skipped when the caller is a
        // preview-less context (AA).
        if (!skipPreview && settingsRepository.get().previewBeforeSend) {
            previewFile = audioFile
            previewTargetId = target.id
            previewDurationMs = durationMs
            _status.value = SendStatus.PreviewReady(durationMs)
            // Keep activeTargetId highlighted so the circle is recognisable
            // while the preview sheet is up.
            return Result.success(-1L)
        }

        // Offline path: if TDLib isn't authenticated OR there's no validated
        // internet, we persist the audio in a permanent location and mark the
        // message QUEUED_OFFLINE. RetryWorker (network-constrained) will pick
        // it up when conditions allow and call TDLib from there.
        val authReady = tdLibClient.authState.value is AuthState.Ready
        val online = connectivityObserver.isOnline()
        if (!authReady || !online) {
            val persistedPath = moveToPersistentQueue(audioFile)
            persistQueuedOffline(
                target = target,
                audioFilePath = persistedPath,
                audioDurationMs = durationMs,
                textContent = null
            )
            currentTargetId = null
            _activeTargetId.value = null
            _status.value = SendStatus.QueuedOffline
            return Result.success(-1L)
        }

        _status.value = SendStatus.Sending
        return try {
            val chatId = target.targetChatId.toLong()
            val sendResult = tdLibClient.send(
                TdApi.SendMessage().apply {
                    this.chatId = chatId
                    this.topicId = target.topicId.toForumTopicOrNull()
                    this.inputMessageContent = TdApi.InputMessageVoiceNote().apply {
                        voiceNote = TdApi.InputFileLocal(audioFile.absolutePath)
                        duration = (durationMs / 1000).toInt()
                    }
                }
            )
            val out = handleSendResult(sendResult)
            // Persist the outgoing message so Stats, History, and success rate
            // actually reflect reality. Without this row nothing downstream
            // knows the user ever sent anything.
            persistOutgoing(
                target = target,
                result = out,
                audioFile = audioFile,
                audioDurationMs = durationMs,
                textContent = null
            )
            if (out.isSuccess) {
                conversationRepository.setLastRepliedAt(target.id, System.currentTimeMillis())
            }
            out
        } catch (e: Exception) {
            Timber.e(e, "MTProto send failed")
            _status.value = SendStatus.Error(e.message ?: "Error enviando")
            persistOutgoing(target, Result.failure(e), audioFile, durationMs, null)
            Result.failure(e)
        } finally {
            currentTargetId = null
            _activeTargetId.value = null
        }
    }

    /**
     * Send a pre-recorded .ogg from `filesDir/voice_shortcuts/` as a voice
     * note to the active (or explicitly chosen) target.
     *
     * Unlike `stopAndSend`, the file is **not** owned by this call — it's a
     * persistent shortcut the user may re-use — so we copy it into the
     * outbox queue on the offline path instead of moving it, and we never
     * delete it on success. `persistOutgoing` receives a null `audioFile`
     * so the History row doesn't try to re-play the shared asset (which
     * could race with the user deleting / re-recording the shortcut).
     */
    suspend fun sendVoiceShortcut(
        audioFilePath: String,
        audioDurationMs: Long,
        targetConversationId: String? = null
    ): Result<Long> {
        val file = java.io.File(audioFilePath)
        if (!file.exists() || file.length() == 0L) {
            _status.value = SendStatus.Error("Audio del atajo no encontrado")
            return Result.failure(IllegalStateException("Voice shortcut file missing: $audioFilePath"))
        }
        val target = resolveTarget(targetConversationId)
        if (target == null) {
            _status.value = SendStatus.Error("Configura un destino en Ajustes")
            return Result.failure(IllegalStateException("No active conversation"))
        }

        val authReady = tdLibClient.authState.value is AuthState.Ready
        val online = connectivityObserver.isOnline()
        if (!authReady || !online) {
            // Copy (not move) — the shortcut must survive the queue run.
            val queued = copyToPersistentQueue(file)
            persistQueuedOffline(
                target = target,
                audioFilePath = queued,
                audioDurationMs = audioDurationMs,
                textContent = null
            )
            _activeTargetId.value = null
            _status.value = SendStatus.QueuedOffline
            return Result.success(-1L)
        }

        _activeTargetId.value = target.id
        _status.value = SendStatus.Sending
        return try {
            val sendResult = tdLibClient.send(
                TdApi.SendMessage().apply {
                    this.chatId = target.targetChatId.toLong()
                    this.topicId = target.topicId.toForumTopicOrNull()
                    this.inputMessageContent = TdApi.InputMessageVoiceNote().apply {
                        voiceNote = TdApi.InputFileLocal(file.absolutePath)
                        duration = (audioDurationMs / 1000).toInt()
                    }
                }
            )
            val out = handleSendResult(sendResult)
            // Reference the shortcut file directly in History so the user
            // can replay what they sent. If they later delete the shortcut
            // the History row remains but its playback becomes a no-op —
            // acceptable, and cheaper than duplicating the file per send.
            persistOutgoing(
                target = target,
                result = out,
                audioFile = file,
                audioDurationMs = audioDurationMs,
                textContent = null
            )
            if (out.isSuccess) {
                conversationRepository.setLastRepliedAt(target.id, System.currentTimeMillis())
                recordingSounds.playSent()
            } else {
                recordingSounds.playError()
            }
            out
        } catch (e: Exception) {
            Timber.e(e, "MTProto voice-shortcut send failed")
            _status.value = SendStatus.Error(e.message ?: "Error enviando")
            persistOutgoing(target, Result.failure(e), audioFile = null, audioDurationMs, textContent = null)
            recordingSounds.playError()
            Result.failure(e)
        } finally {
            _activeTargetId.value = null
        }
    }

    suspend fun sendQuickReply(text: String, targetConversationId: String? = null): Result<Long> {
        val target = resolveTarget(targetConversationId)
        if (target == null) {
            _status.value = SendStatus.Error("Configura un destino en Ajustes")
            return Result.failure(IllegalStateException("No active conversation"))
        }
        // Offline path (same as voice): queue for RetryWorker instead of erroring out.
        val authReady = tdLibClient.authState.value is AuthState.Ready
        val online = connectivityObserver.isOnline()
        if (!authReady || !online) {
            persistQueuedOffline(
                target = target,
                audioFilePath = null,
                audioDurationMs = 0L,
                textContent = text
            )
            _activeTargetId.value = null
            _status.value = SendStatus.QueuedOffline
            return Result.success(-1L)
        }
        _activeTargetId.value = target.id
        _status.value = SendStatus.Sending
        return try {
            val sendResult = tdLibClient.send(
                TdApi.SendMessage().apply {
                    this.chatId = target.targetChatId.toLong()
                    this.topicId = target.topicId.toForumTopicOrNull()
                    this.inputMessageContent = TdApi.InputMessageText().apply {
                        this.text = TdApi.FormattedText(text, emptyArray())
                    }
                }
            )
            val out = handleSendResult(sendResult)
            persistOutgoing(target, out, audioFile = null, audioDurationMs = 0L, textContent = text)
            if (out.isSuccess) {
                conversationRepository.setLastRepliedAt(target.id, System.currentTimeMillis())
                recordingSounds.playSent()
            } else {
                recordingSounds.playError()
            }
            out
        } catch (e: Exception) {
            Timber.e(e, "MTProto text send failed")
            _status.value = SendStatus.Error(e.message ?: "Error enviando")
            persistOutgoing(target, Result.failure(e), audioFile = null, audioDurationMs = 0L, textContent = text)
            recordingSounds.playError()
            Result.failure(e)
        } finally {
            _activeTargetId.value = null
        }
    }

    /**
     * Play the parked preview file. Invoked from the preview sheet.
     */
    suspend fun previewPlay() {
        val f = previewFile ?: return
        _status.value = SendStatus.PreviewPlaying
        audioPlayer.play(f, speed = 1f) { _ ->
            _status.value = SendStatus.PreviewReady(previewDurationMs)
        }
    }

    /**
     * Discard the parked preview + delete the file. Back to Idle.
     */
    suspend fun cancelPreview() {
        audioPlayer.stop()
        previewFile?.let { runCatching { it.delete() } }
        previewFile = null
        previewTargetId = null
        previewDurationMs = 0L
        _activeTargetId.value = null
        _status.value = SendStatus.Idle
        // v1.22.0 — preview discarded; resume any messages parked while
        // the mic / preview was tying up the audio path.
        playReplyUseCase.drainPendingIfAutoPlay()
    }

    /**
     * User confirmed the preview — resume the normal send path with the
     * same target + file.
     */
    suspend fun confirmPreview(): Result<Long> {
        val result = confirmPreviewInner()
        playReplyUseCase.drainPendingIfAutoPlay()
        return result
    }

    private suspend fun confirmPreviewInner(): Result<Long> {
        val audioFile = previewFile ?: return Result.failure(IllegalStateException("No preview"))
        val targetId = previewTargetId
        val durationMs = previewDurationMs
        audioPlayer.stop()
        previewFile = null
        previewTargetId = null
        previewDurationMs = 0L

        val target = resolveTarget(targetId) ?: run {
            _activeTargetId.value = null
            _status.value = SendStatus.Error("Configura un destino en Ajustes")
            return Result.failure(IllegalStateException("No active conversation"))
        }

        val authReady = tdLibClient.authState.value is AuthState.Ready
        val online = connectivityObserver.isOnline()
        if (!authReady || !online) {
            val persistedPath = moveToPersistentQueue(audioFile)
            persistQueuedOffline(
                target = target,
                audioFilePath = persistedPath,
                audioDurationMs = durationMs,
                textContent = null
            )
            _activeTargetId.value = null
            _status.value = SendStatus.QueuedOffline
            return Result.success(-1L)
        }

        _status.value = SendStatus.Sending
        return try {
            val sendResult = tdLibClient.send(
                TdApi.SendMessage().apply {
                    this.chatId = target.targetChatId.toLong()
                    this.topicId = target.topicId.toForumTopicOrNull()
                    this.inputMessageContent = TdApi.InputMessageVoiceNote().apply {
                        voiceNote = TdApi.InputFileLocal(audioFile.absolutePath)
                        duration = (durationMs / 1000).toInt()
                    }
                }
            )
            val out = handleSendResult(sendResult)
            persistOutgoing(
                target = target,
                result = out,
                audioFile = audioFile,
                audioDurationMs = durationMs,
                textContent = null
            )
            if (out.isSuccess) {
                conversationRepository.setLastRepliedAt(target.id, System.currentTimeMillis())
            }
            out
        } catch (e: Exception) {
            Timber.e(e, "confirmPreview send failed")
            _status.value = SendStatus.Error(e.message ?: "Error enviando")
            persistOutgoing(target, Result.failure(e), audioFile, durationMs, null)
            Result.failure(e)
        } finally {
            _activeTargetId.value = null
        }
    }

    /**
     * Move a freshly recorded audio file from cacheDir into a permanent
     * outbox under filesDir. cacheDir is eviction-eligible under memory
     * pressure, which would eat queued voice notes while the user is
     * offline — we can't risk that.
     */
    private fun moveToPersistentQueue(src: File): String {
        val outbox = File(context.filesDir, "audio_outbox").apply { mkdirs() }
        val dst = File(outbox, src.name)
        return try {
            if (src.renameTo(dst)) dst.absolutePath
            else {
                src.copyTo(dst, overwrite = true)
                src.delete()
                dst.absolutePath
            }
        } catch (e: Exception) {
            Timber.w(e, "moveToPersistentQueue failed; keeping cacheDir path")
            src.absolutePath
        }
    }

    /**
     * Copy (not move) a persistent file into the outbox queue. Used for
     * voice shortcuts where the caller needs to preserve the source file
     * for re-use. Filename is prefixed with a timestamp so concurrent
     * queued sends of the same shortcut don't clobber each other.
     */
    private fun copyToPersistentQueue(src: File): String {
        val outbox = File(context.filesDir, "audio_outbox").apply { mkdirs() }
        val dst = File(outbox, "${System.currentTimeMillis()}-${src.name}")
        return try {
            src.copyTo(dst, overwrite = true)
            dst.absolutePath
        } catch (e: Exception) {
            Timber.w(e, "copyToPersistentQueue failed; keeping source path")
            src.absolutePath
        }
    }

    /**
     * Insert the outgoing message with state=QUEUED_OFFLINE so RetryWorker
     * picks it up when network comes back. Used for both voice and text.
     */
    private suspend fun persistQueuedOffline(
        target: Conversation,
        audioFilePath: String?,
        audioDurationMs: Long,
        textContent: String?
    ) {
        val now = System.currentTimeMillis()
        val message = Message(
            id = UUID.randomUUID().toString(),
            conversationId = target.id,
            direction = MessageDirection.OUTGOING,
            state = MessageState.QUEUED_OFFLINE,
            audioFilePath = audioFilePath,
            audioDurationMs = if (audioDurationMs > 0) audioDurationMs else null,
            textContent = textContent,
            createdAt = now
        )
        runCatching { messageRepository.insert(message) }
            .onFailure { Timber.e(it, "Failed to persist QUEUED_OFFLINE message") }
        // Kick the worker; it'll wait for network if needed, then drain the
        // outbox. Wrapped in runCatching because WorkManager may not be
        // initialized in unit-test environments — failing to enqueue isn't
        // fatal: the worker also runs on its own periodic schedule and the
        // row is already in QUEUED_OFFLINE for it to pick up later.
        runCatching { RetryWorker.enqueueOnce(context) }
            .onFailure { Timber.w(it, "RetryWorker.enqueueOnce skipped (WorkManager not ready?)") }
    }

    /**
     * Record the outgoing message in the DB so Stats / History / success-rate
     * calculations have something to count. State mirrors what actually happened
     * on the wire: SENT if TDLib accepted it, FAILED otherwise.
     */
    private suspend fun persistOutgoing(
        target: Conversation,
        result: Result<Long>,
        audioFile: File?,
        audioDurationMs: Long,
        textContent: String?
    ) {
        val now = System.currentTimeMillis()
        val message = Message(
            id = UUID.randomUUID().toString(),
            conversationId = target.id,
            direction = MessageDirection.OUTGOING,
            state = if (result.isSuccess) MessageState.SENT else MessageState.FAILED,
            telegramMessageId = result.getOrNull(),
            audioFilePath = audioFile?.absolutePath,
            audioDurationMs = if (audioDurationMs > 0) audioDurationMs else null,
            textContent = textContent,
            createdAt = now,
            sentAt = if (result.isSuccess) now else null,
            errorMessage = result.exceptionOrNull()?.message
        )
        runCatching { messageRepository.insert(message) }
            .onFailure { Timber.w(it, "Failed to persist outgoing message") }
    }

    private suspend fun resolveTarget(id: String?) =
        if (id != null) conversationRepository.getById(id) else conversationRepository.getActive()

    fun reset() {
        _status.value = SendStatus.Idle
    }

    private fun handleSendResult(result: TdApi.Object): Result<Long> = when (result) {
        is TdApi.Message -> {
            _status.value = SendStatus.Sent(result.id)
            Result.success(result.id)
        }
        is TdApi.Error -> {
            val msg = "Telegram ${result.code}: ${result.message}"
            _status.value = SendStatus.Error(msg)
            Result.failure(Exception(msg))
        }
        else -> {
            _status.value = SendStatus.Error("Respuesta inesperada de TDLib")
            Result.failure(Exception("Unexpected TDLib response: ${result.javaClass.simpleName}"))
        }
    }
}
