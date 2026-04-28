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
            Timber.i("playVoiceMessage ${message.id} → enqueue (in-call)")
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
            Timber.i("playVoiceMessage ${message.id} → enqueue (autoPlayReplies=false)")
            enqueuePending(message, PendingReason.SILENT_MODE)
            return
        }

        // Audit-HIGH-4: take the start-lock BEFORE inspecting isPlaying so two
        // concurrent calls can't both pass the check. If something is already
        // playing, this caller queues and returns without releasing until the
        // status is persisted.
        //
        // v1.20.18: the busy check ONLY applies to user-initiated arrivals
        // (respectUserGates=true). When respectUserGates=false we're being
        // called from playNext() right after a prior playback's onCompletion
        // fired — the AudioPlayer.isPlaying flag is reliably false here,
        // but if some OEM's MediaPlayer were ever briefly inconsistent the
        // re-enqueue would strand this message in memory (its persistent
        // pending row was already deleted by playNext) and break the chain.
        // Trusting the call-site over the flag keeps the queue draining.
        playbackStartLock.withLock {
            if (respectUserGates && audioPlayer.isPlaying()) {
                Timber.i("playVoiceMessage ${message.id} → enqueue (audio already playing)")
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
        if (text == null) {
            // v1.20.19 — no usable summary/transcription. Most common
            // cause: on-device speech recognition isn't available for the
            // user's locale (transcribe returns NotInstalled /
            // LanguageUnavailable), so the chained summarize never runs
            // either, and we end up here with a voice message that has
            // no text representation. Pre-v1.20.19 we returned silently,
            // which left the user staring at a "1" badge on the circle
            // with nothing actually playing. Fall back to the original
            // audio so auto-play still does its job.
            if (!message.audioFilePath.isNullOrBlank()) {
                Timber.i(
                    "playSummaryOrTranscription ${message.id} → no text after " +
                    "summarize/transcribe; falling back to original audio"
                )
                playVoiceMessage(message, isCallActive, respectUserGates)
                return
            }
            Timber.w(
                "playSummaryOrTranscription ${message.id} → no readable text " +
                "and no audio path; nothing to play (state=${message.state})"
            )
            return
        }
        val settings = settingsRepository.get()
        if (respectUserGates && isCallActive) {
            Timber.i("playSummaryOrTranscription ${message.id} → enqueue (in-call)")
            enqueuePending(message, PendingReason.IN_CALL); return
        }
        // v1.20.11: silentMode + autoPlayReplies collapsed — !autoPlay = queue.
        if (respectUserGates && !settings.autoPlayReplies) {
            Timber.i("playSummaryOrTranscription ${message.id} → enqueue (autoPlayReplies=false)")
            enqueuePending(message, PendingReason.SILENT_MODE); return
        }

        // Same coordination as playTextMessage: refuse to speak over an
        // in-flight audio/TTS playback; enqueue the message instead so
        // playNext picks it up after the current one finishes.
        //
        // v1.20.18: gate the busy check on respectUserGates. Android's
        // TextToSpeech.isSpeaking() has a tiny window after onDone fires
        // where it can still return true; if a queued message arrives at
        // playNext during that window, the old code re-enqueued it in
        // memory while the persistent pending row was already gone, so
        // the queue stalled and the user had to manually drain. When
        // we're here from playNext (respectUserGates=false), the
        // previous utterance's onDone already fired and we trust that
        // we're safe to start the next utterance — TtsPlayer.speak does
        // QUEUE_FLUSH internally, which makes this safe even if the
        // engine is genuinely lingering.
        playbackStartLock.withLock {
            if (respectUserGates && (audioPlayer.isPlaying() || ttsPlayer.isSpeaking())) {
                Timber.i(
                    "playSummaryOrTranscription ${message.id} → enqueue " +
                    "(audio=${audioPlayer.isPlaying()} tts=${ttsPlayer.isSpeaking()})"
                )
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
            Timber.i("playTextMessage ${message.id} → enqueue (in-call)")
            enqueuePending(message, PendingReason.IN_CALL)
            return
        }
        // v1.20.11: silentMode + autoPlayReplies collapsed — !autoPlay = queue
        // for manual drain via HeroHeader's "Play N pending" button. The
        // row used to be left in RECEIVED state with no surface to play
        // it from; now it's persisted as PENDING_PLAYBACK so it actually
        // shows up in the queue and the count surfaces in the UI.
        if (respectUserGates && !settings.autoPlayReplies) {
            Timber.i("playTextMessage ${message.id} → enqueue (autoPlayReplies=false)")
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
        //
        // v1.20.18: see playSummaryOrTranscription for why the busy
        // check is gated on respectUserGates — same isSpeaking-race
        // story.
        playbackStartLock.withLock {
            if (respectUserGates && (audioPlayer.isPlaying() || ttsPlayer.isSpeaking())) {
                Timber.i(
                    "playTextMessage ${message.id} → enqueue " +
                    "(audio=${audioPlayer.isPlaying()} tts=${ttsPlayer.isSpeaking()})"
                )
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
     * Stop whatever is currently playing (audio file + in-flight TTS).
     *
     * v1.20.11: NO LONGER clears the pending queue. The previous behaviour
     * cleared the in-memory queue but left the persisted PendingPlaybackDao
     * rows intact, so the messages reappeared in `pendingCount` on the next
     * app launch (via `restorePendingQueue`) — visible-then-invisible state
     * the user couldn't trust. Now the queue is a durable user-facing surface
     * (drained via HeroHeader's "Play N pending" button), so "stop" only
     * stops the current playback; queued messages stay queued. The
     * pending-replies notification is kept in sync with the surviving count.
     */
    fun stopAll() {
        audioPlayer.stop()
        // Also stop TTS — a summary/transcription TTS in flight when
        // the user starts recording would otherwise keep speaking into
        // the mic (audio contamination of the new recording).
        ttsPlayer.stop()
        currentPlayingId = null
        val remaining = pendingCount()
        if (remaining > 0) {
            _playbackStatus.value = PlaybackStatus.PendingCount(remaining)
            pendingReplyNotifier.notifyPending(remaining)
        } else {
            _playbackStatus.value = PlaybackStatus.Idle
            pendingReplyNotifier.cancel()
        }
        // BUG-44 FIX: Cancel and recreate playback scope so MediaPlayer
        // completion callbacks for the just-stopped playback don't fire
        // DB updates against a "now playing" row that's no longer playing.
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
     * Drain the queued pending messages sequentially. Triggered by:
     *   - HeroHeader's "Play N pending" button (v1.20.11)
     *   - Android Auto's pending-count tile
     *   - Voice command "REPETIR"
     *
     * Pops the first queued message and starts playback; the audio-
     * completion → playNext() chain handles the rest. Iterating manually
     * with a for-loop would re-enqueue every item past the first, since
     * playVoiceMessage gates on `audioPlayer.isPlaying()`.
     */
    suspend fun playAllPending() {
        // v1.20.11 — if a one-off playback is already in flight (e.g. the
        // user tapped Replay-last, then immediately tapped Play N pending),
        // hard-stop it so the queue actually starts at message #1. Without
        // this, the first queued message would hit
        // `playVoiceMessage`'s isPlaying() gate and get re-enqueued at the
        // BACK of the queue while its DAO row was already deleted —
        // ghosted message after the next app restart.
        if (audioPlayer.isPlaying() || ttsPlayer.isSpeaking()) {
            audioPlayer.stop()
            ttsPlayer.stop()
            currentPlayingId = null
        }
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
     * Pending Queue Now-Playing transport: stop the current message and
     * jump to the next queued one. No-op when the queue is empty (the
     * current playback is just stopped). Used by the skip-next button
     * in the Pending Queue screen's Now Playing panel.
     */
    suspend fun skipNext() {
        audioPlayer.stop()
        ttsPlayer.stop()
        currentPlayingId = null
        playNext()
    }

    /**
     * Restart the currently-playing message from the beginning. Used by
     * the skip-previous button in the Pending Queue's Now Playing panel.
     * If nothing is playing, no-op. We don't track played history, so
     * "previous" can't go back to a finished message — restart is the
     * sensible interpretation.
     */
    suspend fun restartCurrent() {
        val playingId = currentPlayingId ?: return
        val msg = messageRepository.getById(playingId) ?: return
        audioPlayer.stop()
        ttsPlayer.stop()
        currentPlayingId = null
        playQueued(msg)
    }

    /** AudioPlayer pass-through for the Now Playing scrubber. Returns null for TTS. */
    fun currentPositionMs(): Int? = audioPlayer.currentPositionMs()
    fun durationMs(): Int? = audioPlayer.durationMs()

    /**
     * Drops every queued message belonging to [conversationId] from the
     * in-memory pending queue, and stops current playback if the message
     * being played belongs to the same conversation. Used by the History
     * "Clear conversation" action so the user doesn't hear ghost
     * messages whose underlying DB rows + audio files have just been
     * wiped. The DB-side pending_playbacks rows cascade-delete via the
     * FK on PendingPlaybackEntity.messageId, so this method only needs
     * to reconcile the in-memory state.
     */
    fun clearForConversation(conversationId: String) {
        val playingFromThisConvo = synchronized(pendingQueue) {
            pendingQueue.removeAll { it.conversationId == conversationId }
            // currentPlayingId is just an id — we don't store the
            // conversationId of the playing message. Caller (HistoryViewModel)
            // already knows the playing message's conversation via
            // playbackStatus, but the simplest and safest action here is
            // to always stop if anything is playing once we've changed
            // the queue. stopAll() doesn't drop the queue (post-v1.20.11).
            audioPlayer.isPlaying() || ttsPlayer.isSpeaking()
        }
        if (playingFromThisConvo) {
            audioPlayer.stop()
            ttsPlayer.stop()
            currentPlayingId = null
        }
        val remaining = pendingCount()
        if (remaining > 0) {
            _playbackStatus.value = PlaybackStatus.PendingCount(remaining)
            pendingReplyNotifier.notifyPending(remaining)
        } else {
            _playbackStatus.value = PlaybackStatus.Idle
            pendingReplyNotifier.cancel()
        }
    }

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
        // Hard-stop any in-flight playback so the requested message can
        // actually become the playing one — otherwise playVoiceMessage's
        // isPlaying() gate would re-enqueue it at the END of the queue
        // and start playing the wrong thing. Same fix as playAllPending.
        if (audioPlayer.isPlaying() || ttsPlayer.isSpeaking()) {
            audioPlayer.stop()
            ttsPlayer.stop()
            currentPlayingId = null
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
