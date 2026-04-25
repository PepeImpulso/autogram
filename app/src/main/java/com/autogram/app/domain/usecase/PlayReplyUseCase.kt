package com.autogram.app.domain.usecase

import com.autogram.app.domain.model.*
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.infrastructure.audio.AudioPlayer
import com.autogram.app.infrastructure.audio.TtsPlayer
import com.autogram.app.infrastructure.persistence.dao.PendingPlaybackDao
import com.autogram.app.infrastructure.persistence.entity.PendingPlaybackEntity
import com.autogram.app.infrastructure.platform.PendingReplyNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caso de uso: reproducir automáticamente las respuestas entrantes.
 * Gestiona la cola de reproducción y el audio focus.
 *
 * Singleton — the queue + current-playing state must be shared across the UI
 * layer (MainViewModel observes `playbackStatus`), the receiver service (posts
 * incoming voices), and the send-voice use case (calls `stopAll()` to
 * interrupt when the user starts recording a reply).
 */
@Singleton
class PlayReplyUseCase @Inject constructor(
    private val audioPlayer: AudioPlayer,
    private val ttsPlayer: TtsPlayer,
    private val messageRepository: MessageRepository,
    private val settingsRepository: SettingsRepository,
    private val pendingPlaybackDao: PendingPlaybackDao,  // BUG-05: Persist pending queue
    private val pendingReplyNotifier: PendingReplyNotifier
) {
    private val _playbackStatus = MutableStateFlow<PlaybackStatus>(PlaybackStatus.Idle)
    val playbackStatus: StateFlow<PlaybackStatus> = _playbackStatus

    // BUG-43 FIX: Thread-safe list for concurrent access from multiple coroutines
    private val pendingQueue = Collections.synchronizedList(mutableListOf<Message>())
    // Audit-HIGH-4: serializes the "start playing" critical section so two
    // concurrent playVoiceMessage() calls can't both see isPlaying==false
    // and race to start MediaPlayer twice, dropping the first message.
    private val playbackStartLock = Mutex()
    @Volatile private var currentPlayingId: String? = null
    // Dedicated scope for MediaPlayer callbacks (instead of runBlocking) —
    // recreated after stopAll() so cancelled work doesn't leak into future
    // playbacks. `@Volatile` because MediaPlayer callbacks fire on the
    // player's internal thread, while stopAll runs on the main / caller
    // thread: without the visibility fence a callback launching via
    // `playbackScope.launch { ... }` could observe a cancelled-but-not-yet
    // -replaced scope and silently drop the DB state update.
    @Volatile private var playbackScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    sealed class PlaybackStatus {
        object Idle : PlaybackStatus()
        data class Playing(val messageId: String) : PlaybackStatus()
        data class Paused(val messageId: String) : PlaybackStatus()
        data class PendingCount(val count: Int) : PlaybackStatus()
    }

    /**
     * Reproduce un mensaje de voz recibido. Si hay reproducción en curso,
     * lo encola para reproducción secuencial.
     */
    suspend fun playVoiceMessage(
        message: Message,
        isCallActive: Boolean = false,
        respectUserGates: Boolean = true
    ) {
        if (respectUserGates && isCallActive) {
            enqueuePending(message, PendingReason.IN_CALL)
            return
        }

        val settings = settingsRepository.get()
        // v1.20.11: silentMode + autoPlayReplies collapsed into a single
        // gate — autoPlayReplies=false now MEANS "queue for manual drain"
        // instead of silently dropping the message. The old SILENT_MODE
        // reason is reused for compatibility with existing pending rows
        // in the persistent queue (PendingPlaybackEntity.reason).
        if (respectUserGates && !settings.autoPlayReplies) {
            enqueuePending(message, PendingReason.SILENT_MODE)
            return
        }

        // Audit-HIGH-4: take the start-lock BEFORE inspecting isPlaying so two
        // concurrent calls can't both pass the check. If something is already
        // playing, this caller queues and returns without releasing until the
        // status is persisted.
        playbackStartLock.withLock {
            if (audioPlayer.isPlaying()) {
                pendingQueue.add(message)
                _playbackStatus.value = PlaybackStatus.PendingCount(pendingQueue.size)
                return
            }

            val audioFile = message.audioFilePath?.let { File(it) }
            if (audioFile == null || !audioFile.exists()) {
                Timber.e("Archivo de audio no encontrado para mensaje ${message.id}")
                return
            }

            currentPlayingId = message.id
            messageRepository.update(message.copy(state = MessageState.PLAYING))
            _playbackStatus.value = PlaybackStatus.Playing(message.id)

            val result = audioPlayer.play(audioFile, speed = settings.playbackSpeed) { playResult ->
            // Use a scoped coroutine (not runBlocking) so the MediaPlayer
            // callback thread doesn't block on DB writes.
            playbackScope.launch {
                if (playResult.isSuccess) {
                    // Keep audioFilePath + file intact after play. The user
                    // must always be able to replay the last incoming voice
                    // via the HeroHeader toggle, so we NO LONGER delete on
                    // play-completion. Deferred cleanup lives in
                    // MtprotoReceiverService.handleVoiceNote: when a NEW
                    // incoming voice arrives on this conversation, the
                    // previously-played file on the same conversation is
                    // the one that gets deleted (if autoDeletePlayed is
                    // on) — it's no longer "the last one". This preserves
                    // the user's space-saving setting while guaranteeing
                    // the latest message is always replayable.
                    messageRepository.update(message.copy(
                        state = MessageState.PLAYED,
                        playedAt = System.currentTimeMillis()
                    ))
                } else {
                    Timber.e(playResult.exceptionOrNull(), "Error reproduciendo mensaje ${message.id}")
                    messageRepository.update(message.copy(
                        state = MessageState.FAILED,
                        errorMessage = playResult.exceptionOrNull()?.message
                    ))
                }
                currentPlayingId = null
                playNext()
            }
        }

            if (result.isFailure) {
                Timber.e(result.exceptionOrNull(), "Error reproduciendo mensaje ${message.id}")
                messageRepository.update(message.copy(
                    state = MessageState.FAILED,
                    errorMessage = result.exceptionOrNull()?.message
                ))
                currentPlayingId = null
                // Dispatch playNext outside this withLock block — it
                // re-enters playVoiceMessage / playTextMessage which
                // try to acquire the same mutex. kotlinx.coroutines
                // Mutex is NOT reentrant, so calling playNext from
                // inside withLock would deadlock the coroutine.
                playbackScope.launch { playNext() }
            }
        } // end playbackStartLock.withLock
    }

    /**
     * User-initiated playback from the history screen. Bypasses silentMode
     * and isCallActive checks — if the user tapped play, they want audio now.
     * If something is already playing, stop it first so the new message takes
     * over instead of being queued behind pending replies.
     */
    suspend fun playManually(message: Message) {
        val audioPath = message.audioFilePath
        if (audioPath.isNullOrBlank()) {
            playTextManually(message)
            return
        }
        if (audioPlayer.isPlaying()) audioPlayer.stop()
        ttsPlayer.stop()
        val audioFile = File(audioPath)
        if (!audioFile.exists()) {
            Timber.w("playManually: missing audio for ${message.id}")
            // Null out the stale path so the "last incoming with
            // audio" flow stops pointing at this row — otherwise the
            // HeroHeader toggle would keep looking enabled and each
            // tap would silently fall into this branch. One row nulled
            // here also prompts the UI to pick the NEXT-most-recent
            // voice (if any) as the replay target.
            if (!message.audioFilePath.isNullOrBlank()) {
                runCatching { messageRepository.update(message.copy(audioFilePath = null)) }
            }
            playTextManually(message.copy(audioFilePath = null))
            return
        }
        currentPlayingId = message.id
        messageRepository.update(message.copy(state = MessageState.PLAYING))
        _playbackStatus.value = PlaybackStatus.Playing(message.id)
        val speed = settingsRepository.get().playbackSpeed
        val result = audioPlayer.play(audioFile, speed = speed) { playResult ->
            playbackScope.launch {
                if (playResult.isSuccess) {
                    messageRepository.update(message.copy(
                        state = MessageState.PLAYED,
                        playedAt = System.currentTimeMillis()
                    ))
                } else {
                    messageRepository.update(message.copy(
                        state = MessageState.FAILED,
                        errorMessage = playResult.exceptionOrNull()?.message
                    ))
                }
                currentPlayingId = null
                _playbackStatus.value = PlaybackStatus.Idle
            }
        }
        if (result.isFailure) {
            messageRepository.update(message.copy(
                state = MessageState.FAILED,
                errorMessage = result.exceptionOrNull()?.message
            ))
            currentPlayingId = null
            _playbackStatus.value = PlaybackStatus.Idle
        }
    }

    /**
     * User-initiated text playback from notification actions / history-like
     * affordances. Unlike autoplay, this bypasses silentMode + autoPlayReplies
     * because the user explicitly pressed Listen.
     */
    suspend fun playTextManually(message: Message) {
        val text = message.summary?.takeIf { it.isNotBlank() }
            ?: message.transcription?.takeIf { it.isNotBlank() }
            ?: message.textContent?.takeIf { it.isNotBlank() }
            ?: return
        if (audioPlayer.isPlaying()) audioPlayer.stop()
        ttsPlayer.stop()
        val settings = settingsRepository.get()
        currentPlayingId = message.id
        messageRepository.update(message.copy(state = MessageState.PLAYING))
        _playbackStatus.value = PlaybackStatus.Playing(message.id)
        val uiLocale = if (settings.languageTag.isNotBlank())
            java.util.Locale.forLanguageTag(settings.languageTag)
        else java.util.Locale.getDefault()
        val detected = TtsPlayer.detectLocale(text, uiLocale)
        ttsPlayer.speak(text, locale = detected) {
            playbackScope.launch {
                messageRepository.update(message.copy(
                    state = MessageState.PLAYED,
                    playedAt = System.currentTimeMillis()
                ))
                currentPlayingId = null
                _playbackStatus.value = PlaybackStatus.Idle
            }
        }
    }

    /**
     * Read an incoming text reply aloud via Android's TextToSpeech. The engine
     * picks the voice matching the user's current UI locale so a Merlin reply
     * in English plays back in an English voice regardless of the device
     * default. Marks the row PLAYED when the utterance finishes.
     */
    /**
     * Speak whatever short text best represents [message] — prefers summary
     * over transcription over original textContent. Used by the receiver
     * service when `summarizeLongMessages` is on, in place of the original
     * audio playback. TTS picks its voice based on the text's detected
     * script so a Spanish summary isn't read with an English voice.
     */
    suspend fun playSummaryOrTranscription(
        message: Message,
        isCallActive: Boolean = false,
        respectUserGates: Boolean = true
    ) {
        val text = message.summary?.takeIf { it.isNotBlank() }
            ?: message.transcription?.takeIf { it.isNotBlank() }
            ?: message.textContent?.takeIf { it.isNotBlank() }
            ?: return
        val settings = settingsRepository.get()
        if (respectUserGates && isCallActive) {
            enqueuePending(message, PendingReason.IN_CALL); return
        }
        // v1.20.11: silentMode + autoPlayReplies collapsed — !autoPlay = queue.
        if (respectUserGates && !settings.autoPlayReplies) {
            enqueuePending(message, PendingReason.SILENT_MODE); return
        }

        // Same coordination as playTextMessage: refuse to speak over an
        // in-flight audio/TTS playback; enqueue the message instead so
        // playNext picks it up after the current one finishes.
        playbackStartLock.withLock {
            if (audioPlayer.isPlaying() || ttsPlayer.isSpeaking()) {
                pendingQueue.add(message)
                _playbackStatus.value = PlaybackStatus.PendingCount(pendingQueue.size)
                return@withLock
            }
            currentPlayingId = message.id
            messageRepository.update(message.copy(state = MessageState.PLAYING))
            _playbackStatus.value = PlaybackStatus.Playing(message.id)
            val uiLocale = if (settings.languageTag.isNotBlank())
                java.util.Locale.forLanguageTag(settings.languageTag)
            else java.util.Locale.getDefault()
            // Content-based override wins over UI locale if the script doesn't
            // match (e.g. UI is English but the summary came out in Spanish).
            val detected = com.autogram.app.infrastructure.audio.TtsPlayer.detectLocale(text, uiLocale)
            ttsPlayer.speak(text, locale = detected) {
                playbackScope.launch {
                    messageRepository.update(message.copy(
                        state = MessageState.PLAYED,
                        playedAt = System.currentTimeMillis()
                    ))
                    currentPlayingId = null
                    _playbackStatus.value = PlaybackStatus.Idle
                    playNext()
                }
            }
        }
    }

    suspend fun playTextMessage(
        message: Message,
        isCallActive: Boolean = false,
        respectUserGates: Boolean = true
    ) {
        val text = message.textContent ?: return
        if (text.isBlank()) return
        // Same gates as playVoiceMessage: in a call, silent mode, or autoplay
        // off — queue for later instead of speaking over other audio. Texts
        // share the same pending queue since they're Messages too, so
        // playAllPending()/playNext() already handle them via playVoiceMessage
        // but text rows have no audioFilePath → fall through to TTS there.
        val settings = settingsRepository.get()
        if (respectUserGates && isCallActive) {
            enqueuePending(message, PendingReason.IN_CALL)
            return
        }
        // v1.20.11: silentMode + autoPlayReplies collapsed — !autoPlay = queue
        // for manual drain via HeroHeader's "Play N pending" button. The
        // row used to be left in RECEIVED state with no surface to play
        // it from; now it's persisted as PENDING_PLAYBACK so it actually
        // shows up in the queue and the count surfaces in the UI.
        if (respectUserGates && !settings.autoPlayReplies) {
            enqueuePending(message, PendingReason.SILENT_MODE)
            return
        }

        // Bug v1.18.x: if Merlin replies with both a voice note AND a
        // text at the same time, handleVoiceNote fires AudioPlayer on
        // the ogg while handleText fires TtsPlayer on the text — two
        // independent engines, two simultaneous voices, user hears a
        // cacophony. Gate TTS behind the same playbackStartLock the
        // audio path uses, and if audio is already speaking, enqueue
        // the text for after — exactly like a late-arriving voice note
        // would be handled.
        playbackStartLock.withLock {
            if (audioPlayer.isPlaying() || ttsPlayer.isSpeaking()) {
                pendingQueue.add(message)
                _playbackStatus.value = PlaybackStatus.PendingCount(pendingQueue.size)
                return@withLock
            }
            currentPlayingId = message.id
            messageRepository.update(message.copy(state = MessageState.PLAYING))
            _playbackStatus.value = PlaybackStatus.Playing(message.id)

            // Detect locale from the text so a Spanish reply isn't read
            // by an English voice. Fallback = user's UI locale (from
            // settings.languageTag, or device default).
            val uiLocale = if (settings.languageTag.isNotBlank())
                java.util.Locale.forLanguageTag(settings.languageTag)
            else java.util.Locale.getDefault()
            val detected = com.autogram.app.infrastructure.audio.TtsPlayer.detectLocale(text, uiLocale)

            ttsPlayer.speak(text, locale = detected) {
                playbackScope.launch {
                    messageRepository.update(message.copy(
                        state = MessageState.PLAYED,
                        playedAt = System.currentTimeMillis()
                    ))
                    currentPlayingId = null
                    playNext()
                }
            }
        }
    }

    /**
     * Pausa la reproducción actual.
     */
    fun pause() {
        audioPlayer.pause()
        currentPlayingId?.let {
            _playbackStatus.value = PlaybackStatus.Paused(it)
        }
    }

    /**
     * Reanuda la reproducción pausada.
     */
    fun resume() {
        audioPlayer.resume()
        currentPlayingId?.let {
            _playbackStatus.value = PlaybackStatus.Playing(it)
        }
    }

    /**
     * Detiene toda reproducción y limpia la cola.
     */
    fun stopAll() {
        audioPlayer.stop()
        // Also stop TTS — a summary/transcription TTS in flight when
        // the user starts recording would otherwise keep speaking into
        // the mic (audio contamination of the new recording).
        ttsPlayer.stop()
        synchronized(pendingQueue) { pendingQueue.clear() }
        currentPlayingId = null
        _playbackStatus.value = PlaybackStatus.Idle
        pendingReplyNotifier.cancel()
        // BUG-44 FIX: Cancel and recreate playback scope
        playbackScope.cancel()
        playbackScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    /**
     * Encola un mensaje para reproducción diferida.
     */
    private suspend fun enqueuePending(message: Message, reason: PendingReason) {
        val playback = PendingPlayback(
            id = UUID.randomUUID().toString(),
            messageId = message.id,
            reason = reason
        )
        messageRepository.update(message.copy(state = MessageState.PENDING_PLAYBACK))
        synchronized(pendingQueue) { pendingQueue.add(message) }
        // BUG-05: Persist to database
        pendingPlaybackDao.insert(PendingPlaybackEntity(
            id = playback.id,
            messageId = playback.messageId,
            enqueuedAt = playback.enqueuedAt,
            reason = playback.reason,
            playedAt = null
        ))
        _playbackStatus.value = PlaybackStatus.PendingCount(pendingCount())
        pendingReplyNotifier.notifyPending(pendingCount())
        Timber.d("Mensaje ${message.id} encolado. Razón: $reason. Pendientes: ${pendingCount()}")
    }

    /**
     * BUG-05: Restore pending queue from database after service restart.
     */
    suspend fun restorePendingQueue() {
        val entities = pendingPlaybackDao.getAll()
        for (entity in entities) {
            val message = messageRepository.getById(entity.messageId) ?: continue
            if (message.state != MessageState.PENDING_PLAYBACK) continue
            // Collections.synchronizedList wraps individual ops (add / remove /
            // size / clear) in a monitor but does NOT cover iteration. The
            // `.any { ... }` scan below iterates; without holding the same
            // lock, a concurrent add from another coroutine can throw
            // ConcurrentModificationException. Wrap the duplicate check AND
            // the subsequent add in a single synchronized block so both the
            // iteration and the decision-plus-mutation are atomic.
            synchronized(pendingQueue) {
                val alreadyQueued = pendingQueue.any { it.id == message.id }
                if (!alreadyQueued) pendingQueue.add(message)
            }
        }
        if (pendingQueue.isNotEmpty()) {
            _playbackStatus.value = PlaybackStatus.PendingCount(pendingCount())
            Timber.d("Restaurados ${pendingQueue.size} mensajes pendientes de reproducción")
        }
    }

    /**
     * Reproduce el siguiente mensaje en cola.
     */
    private suspend fun playNext() {
        val isEmpty: Boolean
        val next: Message?
        synchronized(pendingQueue) {
            isEmpty = pendingQueue.isEmpty()
            next = if (isEmpty) null else pendingQueue.removeAt(0)
        }
        // BUG-56 FIX: Notifier calls happen outside the synchronized block to
        // avoid holding the queue lock during NotificationManager I/O.
        if (isEmpty || next == null) {
            _playbackStatus.value = PlaybackStatus.Idle
            pendingReplyNotifier.cancel()
            return
        }
        pendingReplyNotifier.notifyPending(pendingCount())
        // BUG-57 FIX: Delete by messageId — pendingPlaybackDao.delete(id) was
        // being called with Message.id but the DAO's id column stores the
        // PendingPlaybackEntity.id (a separate UUID). Records were never deleted,
        // so restorePendingQueue() on app restart would re-enqueue old messages.
        playbackScope.launch {
            try {
                pendingPlaybackDao.deleteByMessageId(next.id)
            } catch (e: Exception) {
                // Don't swallow silently — if delete keeps failing the row
                // gets re-enqueued on every app restart via restorePendingQueue().
                Timber.w(e, "Failed to delete pending playback for ${next.id}")
            }
        }
        // Text messages live in the same queue (see enqueuePending) but must
        // be dispatched to the TTS path — playVoiceMessage would fail looking
        // for an audio file that never existed.
        playQueued(next)
    }

    /**
     * Reproduce todos los mensajes pendientes (cuando se desactiva el modo silencio o termina la llamada).
     */
    suspend fun playAllPending() {
        // DON'T drain the queue with a for-loop calling playVoiceMessage:
        // audioPlayer.play is asynchronous and returns immediately, so
        // the second iteration's playVoiceMessage sees isPlaying()==true
        // and re-enqueues the message — every item after the first was
        // effectively re-queued in the exact same order, so nothing
        // ever drained. Instead, kick off the first message and let the
        // audio-completion → playNext() chain drain the rest the same
        // way an incoming reply drains the queue.
        val first: Message? = synchronized(pendingQueue) {
            if (pendingQueue.isEmpty()) null else pendingQueue.removeAt(0)
        }
        if (first == null) return
        pendingReplyNotifier.notifyPending(pendingCount())
        playbackScope.launch {
            try {
                pendingPlaybackDao.deleteByMessageId(first.id)
            } catch (e: Exception) {
                Timber.w(e, "Failed to delete pending playback for ${first.id}")
            }
        }
        playQueued(first)
    }

    /**
     * Devuelve el número de mensajes pendientes de reproducción.
     */
    fun pendingCount(): Int = synchronized(pendingQueue) { pendingQueue.size }

    /**
     * Live count of pending messages — for UI surfaces that need to react
     * (e.g. HeroHeader's "Play N pending" button toggling visibility).
     * The internal queue isn't a Flow itself; we re-publish via the
     * existing playbackStatus stream which already emits PendingCount on
     * every enqueue / drain. UI binds to playbackStatus and falls back
     * to [pendingCount] for the initial value.
     */
    val pendingCountFlow: Flow<Int>
        get() = playbackStatus.map { status ->
            when (status) {
                is PlaybackStatus.PendingCount -> status.count
                else -> pendingCount()
            }
        }

    /**
     * E (v1.20.11): user tapped a specific pending message in History and
     * wants playback to start from there, then chain through the rest of
     * the queue. Removes [message] from its current position in the
     * pending queue, plays it immediately, and lets playNext() drain
     * the remainder. If [message] isn't actually in the queue (e.g. the
     * user tapped a long-played row), falls back to [playManually].
     */
    suspend fun playFromHere(message: Message) {
        val wasQueued = synchronized(pendingQueue) {
            val idx = pendingQueue.indexOfFirst { it.id == message.id }
            if (idx >= 0) {
                pendingQueue.removeAt(idx)
                true
            } else false
        }
        if (!wasQueued) {
            playManually(message)
            return
        }
        playbackScope.launch {
            try {
                pendingPlaybackDao.deleteByMessageId(message.id)
            } catch (e: Exception) {
                Timber.w(e, "Failed to delete pending playback for ${message.id}")
            }
        }
        pendingReplyNotifier.notifyPending(pendingCount())
        playQueued(message)
    }

    private suspend fun playQueued(message: Message) {
        val settings = settingsRepository.get()
        val hasReadableSummary = !message.summary.isNullOrBlank() ||
            !message.transcription.isNullOrBlank() ||
            !message.textContent.isNullOrBlank()
        if (settings.summarizeLongMessages &&
            settings.summaryEngine != SummaryEngineType.NONE &&
            hasReadableSummary
        ) {
            playSummaryOrTranscription(message, respectUserGates = false)
        } else if (!message.audioFilePath.isNullOrBlank()) {
            playVoiceMessage(message, respectUserGates = false)
        } else if (!message.textContent.isNullOrBlank()) {
            playTextMessage(message, respectUserGates = false)
        }
    }
}
