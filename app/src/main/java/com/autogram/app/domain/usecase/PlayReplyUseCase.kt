package com.autogram.app.domain.usecase

import com.autogram.app.domain.model.*
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.infrastructure.audio.AudioPlayer
import com.autogram.app.infrastructure.audio.AudioRecorder
import com.autogram.app.infrastructure.audio.AudioFocusDeniedException
import com.autogram.app.infrastructure.audio.AudioFocusLostException
import com.autogram.app.infrastructure.audio.TtsPlayer
import com.autogram.app.infrastructure.persistence.dao.PendingPlaybackDao
import com.autogram.app.infrastructure.persistence.entity.PendingPlaybackEntity
import com.autogram.app.infrastructure.platform.PendingReplyNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val audioRecorder: AudioRecorder,
    private val messageRepository: MessageRepository,
    private val settingsRepository: SettingsRepository,
    private val conversationRepository: ConversationRepository,
    private val pendingPlaybackDao: PendingPlaybackDao,  // BUG-05: Persist pending queue
    private val pendingReplyNotifier: PendingReplyNotifier,
    private val mediaPlayback: com.autogram.app.service.AutogramMediaPlaybackController
) {

    init {
        // v1.22.1 — bridge AA / Bluetooth-car / lock-screen transport
        // taps back into our use case. The MediaSessionCompat callback
        // fires on the system binder thread, so each handler dispatches
        // through `playbackScope` (Dispatchers.IO) for the suspend
        // methods.
        mediaPlayback.setListener(object : com.autogram.app.service.AutogramMediaPlaybackController.Listener {
            override fun onPlay() {
                playbackScope.launch {
                    if (isPlaybackActive()) {
                        // Already playing — treat the tap as resume after a paused-by-OS state.
                        resume()
                    } else if (pendingCount() > 0) {
                        // Idle with queue → drain it.
                        playAllPending()
                    } else {
                        resume() // no-op when nothing's loaded
                    }
                }
            }
            // v1.22.3 — every callback dispatches through playbackScope so
            // none runs on the MediaSession binder thread. Doing playback and
            // database work synchronously there risks ANR-ing the car UI.
            override fun onPause() { playbackScope.launch { pause() } }
            override fun onStop() { playbackScope.launch { stopAll() } }
            override fun onSkipToNext() { playbackScope.launch { skipNext() } }
            override fun onSkipToPrevious() {
                playbackScope.launch {
                    // Prefer real previous-message navigation; fall back to
                    // "restart current" when there's no history yet so AA
                    // / lock-screen / BT controls always do *something*
                    // useful instead of being silently no-op'd.
                    if (canSkipPrevious.value) skipPrevious() else restartCurrent()
                }
            }
            override fun onPlayFromMediaId(mediaId: String, extras: android.os.Bundle?) {
                playbackScope.launch {
                    val msg = messageRepository.getById(mediaId)
                    if (msg == null) {
                        // Message was deleted between catalog load and AA tap.
                        // Without this, AA would update its UI to PLAYING
                        // because the callback returned successfully — and
                        // the user would tap pause / stop on a session
                        // that has nothing to pause. Reset session state
                        // so AA / lock-screen reflect "nothing's playing".
                        Timber.w("onPlayFromMediaId: no message for $mediaId — reverting session to idle")
                        mediaPlayback.markStopped()
                        return@launch
                    }
                    playFromHere(msg)
                }
            }
            override fun onSeekTo(positionMs: Long) {
                // No coroutine launch needed — seekTo() is non-blocking
                // (MediaPlayer.seekTo is async; AA's session state push
                // is fire-and-forget on playbackScope already).
                seekTo(positionMs.toInt())
            }
            override fun onPlayFromSearch(query: String, extras: android.os.Bundle?) {
                // v1.22.4 — entry point for "Hey Google, play <X> on Autogram"
                // dispatched from AA / Assistant. Empty query == "play
                // anything" (drain pending). Non-empty query is matched
                // case-insensitively against pinned conversation titles;
                // first hit's most-recent unread voice/text is played. If
                // nothing matches, fall through to draining pending so the
                // user always hears *something* rather than silence.
                playbackScope.launch {
                    if (query.isBlank()) {
                        playAllPending()
                        return@launch
                    }
                    val q = query.trim().lowercase()
                    val match = runCatching { conversationRepository.getAll() }
                        .getOrDefault(emptyList())
                        .firstOrNull { conv ->
                            val title = conv.topicName?.takeIf { it.isNotBlank() } ?: conv.targetTitle
                            title?.lowercase()?.contains(q) == true
                        }
                    if (match == null) {
                        Timber.i("onPlayFromSearch: no convo matched '$query', draining pending")
                        playAllPending()
                        return@launch
                    }
                    val recent = runCatching {
                        messageRepository.observeAll(match.id).first()
                            .maxByOrNull { it.createdAt }
                    }.getOrNull()
                    if (recent != null) playFromHere(recent) else playAllPending()
                }
            }
        })
    }

    private val _playbackStatus = MutableStateFlow<PlaybackStatus>(PlaybackStatus.Idle)
    val playbackStatus: StateFlow<PlaybackStatus> = _playbackStatus

    // BUG-43 FIX: Thread-safe list for concurrent access from multiple coroutines
    private val pendingQueue = Collections.synchronizedList(mutableListOf<Message>())
    // Bounded history of messages that have just finished playing — feeds the
    // skip-previous control so the user can step back through the queue.
    // Capped to keep memory tiny; only the most recent few are useful in a
    // skip-back UI flow.
    private val playedHistory = ArrayDeque<Message>(PLAYED_HISTORY_CAP)
    private val playedHistoryLock = Any()
    private val _canSkipPrevious = MutableStateFlow(false)
    val canSkipPrevious: StateFlow<Boolean> = _canSkipPrevious.asStateFlow()
    // Audit-HIGH-4: serializes the "start playing" critical section so two
    // concurrent playVoiceMessage() calls can't both see isPlaying==false
    // and race to start MediaPlayer twice, dropping the first message.
    private val playbackStartLock = Mutex()
    // Only one queue drain may select + start an item at a time. The start
    // mutex alone is insufficient because queued calls intentionally bypass
    // the user-facing busy gate.
    private val queueDrainLock = Mutex()
    private val playbackSessionLock = Any()
    private data class PlaybackSession(val token: Long, val messageId: String)
    private var nextPlaybackSessionToken: Long = 0L
    private var currentPlaybackSession: PlaybackSession? = null
    @Volatile private var currentPlayingId: String? = null
    // Guards the small gap between "user tapped record" and the recorder
    // backend reporting isRecording=true. Without this latch, an incoming
    // reply can begin playback in that startup window and talk over the
    // outgoing voice note the user is trying to record.
    @Volatile private var userRecordingGateActive: Boolean = false
    // Stable scope for engine callbacks. Playback sessions, not scope
    // cancellation, suppress late callbacks; cancelling this scope could also
    // cancel the only durable requeue after Pause/Stop.
    private val playbackScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Includes TTS requests accepted by the engine but not audible yet. */
    private fun isPlaybackActive(): Boolean =
        currentPlayingId != null || audioPlayer.isPlaying() || ttsPlayer.isSpeaking()

    private fun beginPlaybackSession(messageId: String): Long = synchronized(playbackSessionLock) {
        val token = ++nextPlaybackSessionToken
        currentPlaybackSession = PlaybackSession(token, messageId)
        currentPlayingId = messageId
        token
    }

    /**
     * Atomically claims the terminal transition for one playback. A late
     * callback from a replaced engine generation cannot claim a newer session.
     */
    private fun claimPlaybackSession(token: Long, messageId: String): Boolean =
        synchronized(playbackSessionLock) {
            val current = currentPlaybackSession
            if (current?.token != token || current.messageId != messageId) {
                false
            } else {
                currentPlaybackSession = null
                currentPlayingId = null
                true
            }
        }

    /** Invalidates the active callback token before an explicit engine stop. */
    private fun invalidatePlaybackSession(expectedMessageId: String? = null): String? =
        synchronized(playbackSessionLock) {
            val current = currentPlaybackSession
            if (expectedMessageId != null && current?.messageId != expectedMessageId) {
                return@synchronized null
            }
            // Advance even when the active session was already claimed by its
            // callback. This invalidates that callback's later queue-drain or
            // MediaSession side effects while its database finalization runs.
            nextPlaybackSessionToken++
            currentPlaybackSession = null
            currentPlayingId = null
            current?.messageId
        }

    private fun isCurrentPlaybackSession(token: Long, messageId: String): Boolean =
        synchronized(playbackSessionLock) {
            val current = currentPlaybackSession
            current?.token == token && current.messageId == messageId
        }

    private fun isPlaybackTransitionCurrent(token: Long): Boolean =
        synchronized(playbackSessionLock) { nextPlaybackSessionToken == token }

    private fun playbackSessionSnapshot(): PlaybackSession? =
        synchronized(playbackSessionLock) { currentPlaybackSession }

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
        // v1.22.0 — never autoplay over an in-flight recording. The mic
        // is hot, so any output would (a) talk over the user mid-thought
        // and (b) leak into the recording's audio. Defer to the queue;
        // [drainPendingIfAutoPlay] resumes after stopAndSend / cancel.
        if (respectUserGates && isUserRecordingOrStarting()) {
            Timber.i("playVoiceMessage ${message.id} → enqueue (user recording)")
            enqueuePending(message, PendingReason.USER_RECORDING)
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
            // Symmetric with playTextMessage / playSummaryOrTranscription:
            // when TTS is reading a text aloud, an incoming voice would start
            // on top of it (two engines, one user) — enqueue instead so the
            // voice plays after the TTS finishes via playNext(). Persist via
            // enqueuePending so the Pending Queue tab list (Room-backed)
            // reflects the deferred messages instead of showing empty while
            // the count badge says >0.
            if (respectUserGates && isPlaybackActive()) {
                Timber.i(
                    "playVoiceMessage ${message.id} → enqueue " +
                    "(audio=${audioPlayer.isPlaying()} tts=${ttsPlayer.isSpeaking()})"
                )
                enqueuePending(message, PendingReason.PLAYBACK_BUSY)
                return
            }

            val audioFile = message.audioFilePath?.let { File(it) }
            if (audioFile == null || !audioFile.exists()) {
                // File vanished from TDLib's cache (eviction, host cleanup,
                // user cleared chat history). Mark the row failed + null the
                // path so it stops being picked up by replay/lastIncoming,
                // and progress the queue if we were dispatched from playNext.
                Timber.w("Audio file missing for ${message.id}; marking failed")
                runCatching {
                    messageRepository.update(message.copy(
                        state = MessageState.FAILED,
                        audioFilePath = null,
                        errorMessage = "Audio file missing"
                    ))
                    pendingPlaybackDao.deleteByMessageId(message.id)
                }
                if (!respectUserGates) {
                    playbackScope.launch { playNext() }
                }
                return
            }

            val sessionToken = beginPlaybackSession(message.id)
            messageRepository.update(message.copy(state = MessageState.PLAYING))
            if (!isCurrentPlaybackSession(sessionToken, message.id)) return@withLock
            _playbackStatus.value = PlaybackStatus.Playing(message.id)
            markMediaSessionPlaying(message, sessionToken)

            val result = audioPlayer.play(
                audioFile,
                speed = settings.playbackSpeed,
                focusMode = settings.audioFocusMode,
                useNavigationUsage = settings.ttsAsNavigationPrompt
            ) { playResult ->
                // Use a scoped coroutine (not runBlocking) so the MediaPlayer
                // callback thread doesn't block on DB writes.
                playbackScope.launch {
                    if (!claimPlaybackSession(sessionToken, message.id)) {
                        Timber.d("Ignoring stale audio callback for ${message.id}")
                        return@launch
                    }
                    val mayAdvance = if (playResult.isSuccess) {
                        markPlaybackCompleted(message)
                        true
                    } else {
                        handlePlaybackFailure(
                            message,
                            playResult.exceptionOrNull() ?: IllegalStateException("Unknown playback failure")
                        )
                    }
                    if (mayAdvance && isPlaybackTransitionCurrent(sessionToken)) playNext()
                    else if (!mayAdvance &&
                        isPlaybackTransitionCurrent(sessionToken) &&
                        !isPlaybackActive()
                    ) {
                        mediaPlayback.markStopped()
                    }
                }
            }

            if (result.isFailure) {
                if (!claimPlaybackSession(sessionToken, message.id)) return@withLock
                val mayAdvance = handlePlaybackFailure(
                    message,
                    result.exceptionOrNull() ?: IllegalStateException("Unknown playback failure")
                )
                // Dispatch playNext outside this withLock block — it
                // re-enters playVoiceMessage / playTextMessage which
                // try to acquire the same mutex. kotlinx.coroutines
                // Mutex is NOT reentrant, so calling playNext from
                // inside withLock would deadlock the coroutine.
                if (mayAdvance) playbackScope.launch {
                    if (isPlaybackTransitionCurrent(sessionToken)) playNext()
                } else if (isPlaybackTransitionCurrent(sessionToken) && !isPlaybackActive()) {
                    mediaPlayback.markStopped()
                }
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
        interruptForReplacement(message.id)
        val audioPath = message.audioFilePath
        if (audioPath.isNullOrBlank()) {
            playTextManually(message)
            return
        }
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
        val sessionToken = beginPlaybackSession(message.id)
        messageRepository.update(message.copy(state = MessageState.PLAYING))
        if (!isCurrentPlaybackSession(sessionToken, message.id)) return
        _playbackStatus.value = PlaybackStatus.Playing(message.id)
        markMediaSessionPlaying(message, sessionToken)
        val manualSettings = settingsRepository.get()
        if (!isCurrentPlaybackSession(sessionToken, message.id)) return
        val result = audioPlayer.play(
            audioFile,
            speed = manualSettings.playbackSpeed,
            focusMode = manualSettings.audioFocusMode,
            useNavigationUsage = manualSettings.ttsAsNavigationPrompt
        ) { playResult ->
            playbackScope.launch {
                if (!claimPlaybackSession(sessionToken, message.id)) {
                    Timber.d("Ignoring stale manual audio callback for ${message.id}")
                    return@launch
                }
                if (playResult.isSuccess) {
                    markPlaybackCompleted(message)
                } else {
                    handlePlaybackFailure(
                        message,
                        playResult.exceptionOrNull() ?: IllegalStateException("Unknown playback failure")
                    )
                }
                if (isPlaybackTransitionCurrent(sessionToken) && !isPlaybackActive()) {
                    if (pendingCount() == 0) _playbackStatus.value = PlaybackStatus.Idle
                    mediaPlayback.markStopped()
                }
            }
        }
        if (result.isFailure) {
            if (!claimPlaybackSession(sessionToken, message.id)) return
            handlePlaybackFailure(
                message,
                result.exceptionOrNull() ?: IllegalStateException("Unknown playback failure")
            )
            if (isPlaybackTransitionCurrent(sessionToken) && !isPlaybackActive()) {
                if (pendingCount() == 0) _playbackStatus.value = PlaybackStatus.Idle
                mediaPlayback.markStopped()
            }
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
        interruptForReplacement(message.id)
        val settings = settingsRepository.get()
        val sessionToken = beginPlaybackSession(message.id)
        messageRepository.update(message.copy(state = MessageState.PLAYING))
        if (!isCurrentPlaybackSession(sessionToken, message.id)) return
        _playbackStatus.value = PlaybackStatus.Playing(message.id)
        markMediaSessionPlaying(message, sessionToken)
        val uiLocale = if (settings.languageTag.isNotBlank())
            java.util.Locale.forLanguageTag(settings.languageTag)
        else java.util.Locale.getDefault()
        val detected = TtsPlayer.detectLocale(text, uiLocale)
        if (!isCurrentPlaybackSession(sessionToken, message.id)) return
        ttsPlayer.speak(
            text,
            locale = detected,
            speed = settings.playbackSpeed,
            onDone = {
                playbackScope.launch {
                    if (!claimPlaybackSession(sessionToken, message.id)) {
                        Timber.d("Ignoring stale manual TTS completion for ${message.id}")
                        return@launch
                    }
                    markPlaybackCompleted(message)
                    if (isPlaybackTransitionCurrent(sessionToken) && !isPlaybackActive()) {
                        _playbackStatus.value = PlaybackStatus.Idle
                        mediaPlayback.markStopped()
                    }
                }
            },
            onError = { error ->
                playbackScope.launch {
                    if (!claimPlaybackSession(sessionToken, message.id)) {
                        Timber.d("Ignoring stale manual TTS error for ${message.id}")
                        return@launch
                    }
                    handlePlaybackFailure(message, error)
                    if (isPlaybackTransitionCurrent(sessionToken) && !isPlaybackActive()) {
                        if (pendingCount() == 0) {
                            _playbackStatus.value = PlaybackStatus.Idle
                        }
                        mediaPlayback.markStopped()
                    }
                }
            }
        )
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
        if (respectUserGates && isUserRecordingOrStarting()) {
            Timber.i("playSummaryOrTranscription ${message.id} → enqueue (user recording)")
            enqueuePending(message, PendingReason.USER_RECORDING); return
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
            if (respectUserGates && isPlaybackActive()) {
                Timber.i(
                    "playSummaryOrTranscription ${message.id} → enqueue " +
                    "(audio=${audioPlayer.isPlaying()} tts=${ttsPlayer.isSpeaking()})"
                )
                enqueuePending(message, PendingReason.PLAYBACK_BUSY)
                return@withLock
            }
            val sessionToken = beginPlaybackSession(message.id)
            messageRepository.update(message.copy(state = MessageState.PLAYING))
            if (!isCurrentPlaybackSession(sessionToken, message.id)) return@withLock
            _playbackStatus.value = PlaybackStatus.Playing(message.id)
            markMediaSessionPlaying(message, sessionToken)
            val uiLocale = if (settings.languageTag.isNotBlank())
                java.util.Locale.forLanguageTag(settings.languageTag)
            else java.util.Locale.getDefault()
            // Content-based override wins over UI locale if the script doesn't
            // match (e.g. UI is English but the summary came out in Spanish).
            val detected = com.autogram.app.infrastructure.audio.TtsPlayer.detectLocale(text, uiLocale)
            ttsPlayer.speak(
                text,
                locale = detected,
                speed = settings.playbackSpeed,
                onDone = {
                    playbackScope.launch {
                        if (!claimPlaybackSession(sessionToken, message.id)) {
                            Timber.d("Ignoring stale summary completion for ${message.id}")
                            return@launch
                        }
                        markPlaybackCompleted(message)
                        if (isPlaybackTransitionCurrent(sessionToken)) playNext()
                    }
                },
                onError = { error ->
                    playbackScope.launch {
                        if (!claimPlaybackSession(sessionToken, message.id)) {
                            Timber.d("Ignoring stale summary error for ${message.id}")
                            return@launch
                        }
                        val mayAdvance = handlePlaybackFailure(message, error)
                        if (mayAdvance && isPlaybackTransitionCurrent(sessionToken)) playNext()
                        else if (!mayAdvance &&
                            isPlaybackTransitionCurrent(sessionToken) &&
                            !isPlaybackActive()
                        ) {
                            mediaPlayback.markStopped()
                        }
                    }
                }
            )
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
        if (respectUserGates && isUserRecordingOrStarting()) {
            Timber.i("playTextMessage ${message.id} → enqueue (user recording)")
            enqueuePending(message, PendingReason.USER_RECORDING)
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
            if (respectUserGates && isPlaybackActive()) {
                Timber.i(
                    "playTextMessage ${message.id} → enqueue " +
                    "(audio=${audioPlayer.isPlaying()} tts=${ttsPlayer.isSpeaking()})"
                )
                enqueuePending(message, PendingReason.PLAYBACK_BUSY)
                return@withLock
            }
            val sessionToken = beginPlaybackSession(message.id)
            messageRepository.update(message.copy(state = MessageState.PLAYING))
            if (!isCurrentPlaybackSession(sessionToken, message.id)) return@withLock
            _playbackStatus.value = PlaybackStatus.Playing(message.id)
            markMediaSessionPlaying(message, sessionToken)

            // Detect locale from the text so a Spanish reply isn't read
            // by an English voice. Fallback = user's UI locale (from
            // settings.languageTag, or device default).
            val uiLocale = if (settings.languageTag.isNotBlank())
                java.util.Locale.forLanguageTag(settings.languageTag)
            else java.util.Locale.getDefault()
            val detected = com.autogram.app.infrastructure.audio.TtsPlayer.detectLocale(text, uiLocale)

            ttsPlayer.speak(
                text,
                locale = detected,
                speed = settings.playbackSpeed,
                onDone = {
                    playbackScope.launch {
                        if (!claimPlaybackSession(sessionToken, message.id)) {
                            Timber.d("Ignoring stale text completion for ${message.id}")
                            return@launch
                        }
                        markPlaybackCompleted(message)
                        if (isPlaybackTransitionCurrent(sessionToken)) playNext()
                    }
                },
                onError = { error ->
                    playbackScope.launch {
                        if (!claimPlaybackSession(sessionToken, message.id)) {
                            Timber.d("Ignoring stale text error for ${message.id}")
                            return@launch
                        }
                        val mayAdvance = handlePlaybackFailure(message, error)
                        if (mayAdvance && isPlaybackTransitionCurrent(sessionToken)) playNext()
                        else if (!mayAdvance &&
                            isPlaybackTransitionCurrent(sessionToken) &&
                            !isPlaybackActive()
                        ) {
                            mediaPlayback.markStopped()
                        }
                    }
                }
            )
        }
    }

    /**
     * Pausa la reproducción actual.
     *
     * v1.23.0-rc.9 — handles TTS playback too. Pre-rc.9 pause() only
     * touched audioPlayer, so when the active playback was TTS (text
     * messages played via playTextMessage / playTextManually, or audio
     * messages played as summaries via playSummaryOrTranscription with
     * summarizeLongMessages=on) tapping pause was a no-op on the TTS
     * engine but still flipped _playbackStatus to Paused — the row's
     * Pause button toggled to Play visually, but the voice kept
     * speaking and the user couldn't silence it. Now we route TTS
     * through ttsPlayer.cancel() (which stops the engine WITHOUT
     * firing the pending onDone, so the autoplay-drain speak()
     * callbacks don't advance the queue under us — a "pause" tap
     * shouldn't auto-skip to the next message), null out the playing
     * id, return the row to the durable pending queue, and flip the session
     * to STOPPED. A re-tap on Play restarts a fresh utterance from the
     * beginning, which is the only sensible "resume" semantic for TTS —
     * Android's TextToSpeech engine has no genuine pause/resume.
     */
    fun pause() {
        if (ttsPlayer.isSpeaking()) {
            val pausedId = invalidatePlaybackSession()
            ttsPlayer.cancel()
            _playbackStatus.value = PlaybackStatus.Idle
            mediaPlayback.markStopped()
            if (pausedId != null) {
                playbackScope.launch {
                    runCatching {
                        val msg = messageRepository.getById(pausedId)
                            ?: return@launch
                        enqueuePending(msg, PendingReason.USER_PAUSED)
                    }
                }
            }
            return
        }
        audioPlayer.pause()
        // v1.22.3 — markPaused gated on currentPlayingId so a pause tap
        // when nothing is playing (e.g. AA's pause button after audio
        // finished naturally and currentPlayingId was cleared in
        // onCompletion) doesn't transition the session from STOPPED to
        // a spurious PAUSED state. Symmetric with resume()'s guard.
        // Pass the audio player's current position so AA's progress bar
        // freezes at the right moment instead of jumping back to 0.
        currentPlayingId?.let {
            _playbackStatus.value = PlaybackStatus.Paused(it)
            val pos = audioPlayer.currentPositionMs()?.toLong() ?: 0L
            mediaPlayback.markPaused(pos)
        }
    }

    /**
     * Reanuda la reproducción pausada.
     *
     * v1.22.3 — flip the session back to PLAYING via [markResumed] so
     * the lock-screen / AA UI updates the same instant audio resumes
     * (instead of waiting on the Room round-trip + async metadata
     * enrich that the previous version's launch did). Metadata stays
     * whatever it was when we paused — we're resuming the same message,
     * no re-fetch needed.
     */
    fun resume() {
        audioPlayer.resume()
        currentPlayingId?.let { id ->
            _playbackStatus.value = PlaybackStatus.Playing(id)
            // Pass the audio player's current position so AA's progress
            // bar resumes from the correct spot rather than restarting
            // its extrapolation from 0.
            val pos = audioPlayer.currentPositionMs()?.toLong() ?: 0L
            mediaPlayback.markResumed(pos)
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
        // Always advance the transition generation. Completion may already
        // have claimed/cleared the active session while its DB work is still
        // running; Stop must still suppress that callback's later drain.
        val interruptedId = invalidatePlaybackSession()
        audioPlayer.stop()
        // Also stop TTS — a summary/transcription TTS in flight when
        // the user starts recording would otherwise keep speaking into
        // the mic (audio contamination of the new recording).
        ttsPlayer.cancel()
        val remaining = pendingCount()
        if (remaining > 0) {
            _playbackStatus.value = PlaybackStatus.PendingCount(remaining)
            pendingReplyNotifier.notifyPending(remaining)
        } else {
            _playbackStatus.value = PlaybackStatus.Idle
            pendingReplyNotifier.cancel()
        }
        mediaPlayback.markStopped()
        if (interruptedId != null) {
            playbackScope.launch {
                val interrupted = messageRepository.getById(interruptedId) ?: return@launch
                enqueuePending(interrupted, PendingReason.USER_PAUSED)
            }
        }
    }

    /**
     * Called before the outgoing recorder starts. This closes the race where
     * autoplay sees audioRecorder.isRecording()==false for a few milliseconds
     * while the UI is already entering the recording flow.
     */
    fun markUserRecordingStarting() {
        userRecordingGateActive = true
    }

    fun clearUserRecordingGate() {
        userRecordingGateActive = false
    }

    private suspend fun markPlaybackCompleted(message: Message) {
        messageRepository.update(message.copy(
            state = MessageState.PLAYED,
            playedAt = System.currentTimeMillis(),
            errorMessage = null
        ))
        pendingPlaybackDao.deleteByMessageId(message.id)
        pushPlayedHistory(message)
    }

    /** Stop a different current item without acknowledging unheard content. */
    private suspend fun interruptForReplacement(replacementMessageId: String) {
        val interruptedId = invalidatePlaybackSession()
        if (interruptedId == null && !audioPlayer.isPlaying() && !ttsPlayer.isSpeaking()) return
        audioPlayer.stop()
        ttsPlayer.cancel()
        if (interruptedId != null && interruptedId != replacementMessageId) {
            messageRepository.getById(interruptedId)?.let {
                enqueuePending(it, PendingReason.USER_PAUSED)
            }
        }
    }

    /**
     * Finalize an engine failure. Focus interruptions remain pending because
     * the engine running to completion does not mean the driver heard them.
     * Other decoder/synthesis failures are terminal and let the queue advance.
     *
     * @return true when the next queued item may be started immediately.
     */
    private suspend fun handlePlaybackFailure(message: Message, error: Throwable): Boolean {
        return if (error is AudioFocusDeniedException || error is AudioFocusLostException) {
            Timber.w(error, "Playback interrupted for ${message.id}; returning it to pending")
            enqueuePending(message, PendingReason.PLAYBACK_INTERRUPTED)
            false
        } else {
            Timber.e(error, "Playback failed for ${message.id}")
            messageRepository.update(message.copy(
                state = MessageState.FAILED,
                errorMessage = error.message
            ))
            pendingPlaybackDao.deleteByMessageId(message.id)
            true
        }
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
        messageRepository.update(message.copy(
            state = MessageState.PENDING_PLAYBACK,
            errorMessage = null
        ))
        val addedToMemory = synchronized(pendingQueue) {
            if (pendingQueue.none { it.id == message.id }) {
                pendingQueue.add(message)
                true
            } else {
                false
            }
        }
        // Keep persistence idempotent. Interrupted queued playback still has
        // its original row because rows are now deleted only after success.
        // When an in-flight item moves to the memory tail, refresh that row's
        // timestamp/reason so a process restart restores the same order.
        val existing = pendingPlaybackDao.getAll().firstOrNull { it.messageId == message.id }
        if (existing == null) {
            pendingPlaybackDao.insert(PendingPlaybackEntity(
                id = playback.id,
                messageId = playback.messageId,
                enqueuedAt = playback.enqueuedAt,
                reason = playback.reason,
                playedAt = null
            ))
        } else if (addedToMemory) {
            pendingPlaybackDao.insert(existing.copy(
                enqueuedAt = playback.enqueuedAt,
                reason = playback.reason,
                playedAt = null
            ))
        }
        _playbackStatus.value = PlaybackStatus.PendingCount(pendingCount())
        pendingReplyNotifier.notifyPending(pendingCount())
        Timber.d("Message ${message.id} enqueued (reason=$reason, pending=${pendingCount()})")
    }

    /**
     * BUG-05: Restore pending queue from database after service restart.
     */
    suspend fun restorePendingQueue() {
        // A process death cannot prove the driver heard the message. Restore
        // stale PLAYING/PAUSED rows as pending instead of acknowledging them.
        runCatching {
            val persistedIds = pendingPlaybackDao.getAll().map { it.messageId }.toMutableSet()
            val stale = messageRepository.getStaleNowPlaying()
            for (message in stale) {
                messageRepository.update(message.copy(
                    state = MessageState.PENDING_PLAYBACK,
                    playedAt = null,
                    errorMessage = null
                ))
                if (persistedIds.add(message.id)) {
                    pendingPlaybackDao.insert(PendingPlaybackEntity(
                        id = UUID.randomUUID().toString(),
                        messageId = message.id,
                        enqueuedAt = message.createdAt,
                        reason = PendingReason.PLAYBACK_INTERRUPTED,
                        playedAt = null
                    ))
                }
            }
            if (stale.isNotEmpty()) {
                Timber.i("Restored ${stale.size} interrupted playback message(s) at startup")
            }
        }.onFailure { Timber.w(it, "Interrupted-playback restore failed") }

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
            Timber.d("Restored ${pendingQueue.size} pending playbacks")
        }
    }

    /**
     * Reproduce el siguiente mensaje en cola.
     */
    private suspend fun playNext() {
        queueDrainLock.withLock {
            // A callback from an older item may arrive after a manual playback
            // has started. Never let that callback pop or replace the new item.
            if (isPlaybackActive()) {
                Timber.d("Queue drain deferred because playback is active")
                return@withLock
            }
            while (true) {
                val next = synchronized(pendingQueue) {
                    if (pendingQueue.isEmpty()) null else pendingQueue.removeAt(0)
                }
                // BUG-56 FIX: notifier calls happen outside the queue monitor.
                if (next == null) {
                    pendingReplyNotifier.cancel()
                    // A manual playback can begin after the initial active
                    // check while this coroutine is selecting the queue head.
                    // Do not let an empty older drain stop its MediaSession.
                    if (!isPlaybackActive()) {
                        _playbackStatus.value = PlaybackStatus.Idle
                        mediaPlayback.markStopped()
                    }
                    return@withLock
                }
                pendingReplyNotifier.notifyPending(pendingCount())
                // Keep the persistent row until playback completes. Invalid
                // rows are finalized and skipped inside playQueued.
                if (playQueued(next)) return@withLock
            }
        }
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
        // Repeated taps and competing MediaSession callbacks converge here.
        // If another item is already active, its terminal callback will drain
        // the queue; cutting it off would lose its completion signal.
        playNext()
    }

    /**
     * v1.22.1 — push the now-playing metadata + PLAYING state to the
     * MediaSessionCompat so Android Auto / Bluetooth car / lock-screen
     * media controls reflect what's currently in flight.
     *
     * v1.22.3 — split into two phases to avoid two earlier traps:
     *   - **Sync** [AutogramMediaPlaybackController.markPlaying] with
     *     `conversation = null`: state PLAYING goes to AA immediately
     *     so the lock-screen / car widget show "now playing" the same
     *     instant audio starts. Title falls back to "Autogram" until
     *     the conversation lookup returns.
     *   - **Async** [AutogramMediaPlaybackController.updateMetadata]
     *     on `playbackScope`: enriches the title with the real contact
     *     name once Room returns. Doesn't touch playback state, so
     *     position isn't reset.
     *
     * Was suspend in an earlier rc, but several callers invoke this
     * from inside `playbackStartLock.withLock { ... }` — making
     * markMediaSessionPlaying suspend held the mutex across the Room
     * read, which serialized unrelated playback starts under load.
     * Sync-fire-then-async-enrich avoids both that mutex hold and the
     * "session shows STOPPED for 50ms" hole the suspend version was
     * trying to fix.
     */
    private fun markMediaSessionPlaying(message: Message, sessionToken: Long) {
        mediaPlayback.markPlaying(message, conversation = null)
        playbackScope.launch {
            val convo = runCatching {
                conversationRepository.getById(message.conversationId)
            }.getOrNull()
            if (convo != null && isCurrentPlaybackSession(sessionToken, message.id)) {
                mediaPlayback.updateMetadata(message, convo)
            }
        }
    }

    /**
     * Devuelve el número de mensajes pendientes de reproducción.
     */
    fun pendingCount(): Int = synchronized(pendingQueue) { pendingQueue.size }

    /**
     * v1.22.0 — called when the user finishes (or cancels) a recording so the
     * autoplay queue resumes if the user has it on. While the mic was hot
     * incoming messages were rerouted to the queue with reason
     * [PendingReason.USER_RECORDING] (see [playVoiceMessage] etc.); this
     * is the resume point. No-op when autoplay is off (queue stays parked
     * for manual drain via the home banner) or when nothing is in flight.
     */
    suspend fun drainPendingIfAutoPlay() {
        if (pendingCount() == 0) return
        if (!settingsRepository.get().autoPlayReplies) return
        // v1.23.0-rc.6 — extend the busy check to include the recorder.
        // Pre-rc.6 we only checked audioPlayer / ttsPlayer; if the user
        // started a NEW recording immediately after sending (or while
        // the network round-trip was still in flight), the queue would
        // start playing back over the new mic. The recorder guard at
        // the playVoiceMessage level already exists, but this defense
        // catches the drain path too.
        if (isUserRecordingOrStarting() || isPlaybackActive()) return
        // v1.23.0-rc.6 — give the user a 2 s beat after the mic releases
        // before the queue starts auto-playing. Without this, the audio
        // of the first queued message steps on the user's own
        // context-switch (they've just finished sending a voice note,
        // and immediate playback of someone else's reply feels jarring
        // and can clip into the recording's tail haptic). After the
        // delay we re-evaluate the guards: if a new recording started,
        // playback already began from another path, or autoplay was
        // toggled off, we abort the drain.
        delay(POST_RECORDING_DRAIN_DELAY_MS)
        if (pendingCount() == 0) return
        if (!settingsRepository.get().autoPlayReplies) return
        if (isUserRecordingOrStarting() || isPlaybackActive()) return
        playAllPending()
    }

    /**
     * Pending Queue Now-Playing transport: stop the current message and
     * jump to the next queued one. No-op when the queue is empty (the
     * current playback is just stopped). Used by the skip-next button
     * in the Pending Queue screen's Now Playing panel.
     */
    suspend fun skipNext() {
        val skippedId = invalidatePlaybackSession()
        audioPlayer.stop()
        ttsPlayer.cancel()
        val skipped = skippedId?.let { messageRepository.getById(it) }
        if (skipped != null) markPlaybackCompleted(skipped)
        playNext()
    }

    /**
     * Step back to the previous-played message. Pops the most recent entry
     * from [playedHistory], stops the current playback, pushes the current
     * message to the front of the pending queue (so resuming the drain picks
     * up where we left off), and re-plays the popped one. No-op when history
     * is empty. Drives the skip-previous transport button.
     */
    suspend fun skipPrevious() {
        val previous = synchronized(playedHistoryLock) {
            if (playedHistory.isEmpty()) return
            playedHistory.removeLast().also {
                _canSkipPrevious.value = playedHistory.isNotEmpty()
            }
        }
        val currentId = invalidatePlaybackSession()
        audioPlayer.stop()
        ttsPlayer.cancel()
        val currentMsg = currentId?.let {
            runCatching { messageRepository.getById(it) }.getOrNull()
        }
        synchronized(pendingQueue) {
            // Re-insert so the drain order becomes [previous, current, ...rest].
            if (currentMsg != null) pendingQueue.add(0, currentMsg)
            pendingQueue.add(0, previous)
        }
        _playbackStatus.value = PlaybackStatus.PendingCount(pendingQueue.size)
        playNext()
    }

    /**
     * Bulk acknowledge: drop every pending playback row, mark the underlying
     * messages as PLAYED, clear the in-memory queue, and stop any current
     * playback. Used by the "Mark all as listened" button in the Pending
     * Queue tab so the user can clear a backlog without hearing it through.
     */
    suspend fun markAllPendingAsListened() {
        val currentId = invalidatePlaybackSession()
        audioPlayer.stop()
        ttsPlayer.cancel()
        val current = currentId?.let { id ->
            runCatching { messageRepository.getById(id) }.getOrNull()
        }
        val drained: List<Message> = synchronized(pendingQueue) {
            val copy = pendingQueue.toList()
            pendingQueue.clear()
            copy
        }
        val acknowledged = buildList {
            current?.let(::add)
            addAll(drained)
        }.distinctBy { it.id }
        val now = System.currentTimeMillis()
        for (m in acknowledged) {
            runCatching {
                pendingPlaybackDao.deleteByMessageId(m.id)
                messageRepository.update(m.copy(state = MessageState.PLAYED, playedAt = now))
            }.onFailure { Timber.w(it, "markAllPendingAsListened failed for ${m.id}") }
        }
        synchronized(playedHistoryLock) {
            playedHistory.clear()
            _canSkipPrevious.value = false
        }
        _playbackStatus.value = PlaybackStatus.Idle
        pendingReplyNotifier.cancel()
        mediaPlayback.markStopped()
    }

    /** Capacity-bounded push to [playedHistory]; refreshes the canSkipPrevious flow. */
    private fun pushPlayedHistory(msg: Message) {
        synchronized(playedHistoryLock) {
            // De-duplicate consecutive entries — a manual replay of the same
            // message shouldn't grow history.
            if (playedHistory.lastOrNull()?.id == msg.id) return
            playedHistory.addLast(msg)
            while (playedHistory.size > PLAYED_HISTORY_CAP) playedHistory.removeFirst()
            _canSkipPrevious.value = playedHistory.isNotEmpty()
        }
    }

    /**
     * Restart the currently-playing message from the beginning. Kept as a
     * separate API from [skipPrevious]: skip goes to the prior message in
     * history, restart re-plays *this* one.
     */
    suspend fun restartCurrent() {
        val playingId = invalidatePlaybackSession() ?: return
        audioPlayer.stop()
        ttsPlayer.cancel()
        val msg = messageRepository.getById(playingId) ?: return
        playQueued(msg)
    }

    /** AudioPlayer pass-through for the Now Playing scrubber. Returns null for TTS. */
    fun currentPositionMs(): Int? = audioPlayer.currentPositionMs()
    fun durationMs(): Int? = audioPlayer.durationMs()

    /**
     * v1.23.0-rc.6 — relays a tap on the Now Playing waveform (or AA's
     * progress bar via [MediaSessionCompat.Callback.onSeekTo]) to the
     * underlying audio player. Also pushes the new position to the
     * MediaSession state synchronously so AA / lock-screen don't briefly
     * snap back to the old position before the next 1 s refresh — without
     * this push, the progress bar shows a one-frame jump-back that reads
     * as a dropped seek.
     */
    fun seekTo(positionMs: Int) {
        audioPlayer.seekTo(positionMs)
        val session = playbackSessionSnapshot() ?: return
        playbackScope.launch {
            val msg = runCatching { messageRepository.getById(session.messageId) }.getOrNull()
                ?: return@launch
            val convo = runCatching {
                conversationRepository.getById(msg.conversationId)
            }.getOrNull()
            if (!isCurrentPlaybackSession(session.token, session.messageId)) return@launch
            // v1.23.0-rc.8 — read playback status inside the launch so a
            // pause that lands between AudioPlayer.seekTo() and the
            // MediaSession push doesn't get clobbered by a stale
            // "playing" snapshot taken on the calling thread. Without
            // this, AA / lock-screen briefly showed "playing at new
            // position" before the pause's own state push corrected it
            // — exactly the one-frame jump rc.6 was trying to prevent.
            if (_playbackStatus.value is PlaybackStatus.Playing) {
                mediaPlayback.markPlaying(msg, convo, positionMs = positionMs.toLong())
            } else {
                mediaPlayback.markPaused(positionMs.toLong())
            }
        }
    }

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
    suspend fun clearForConversation(conversationId: String) {
        val current = currentPlayingId?.let { id ->
            runCatching { messageRepository.getById(id) }.getOrNull()
        }
        synchronized(pendingQueue) {
            pendingQueue.removeAll { it.conversationId == conversationId }
        }
        val playingFromThisConvo = current?.conversationId == conversationId
        if (playingFromThisConvo) {
            invalidatePlaybackSession(expectedMessageId = current.id)
            audioPlayer.stop()
            ttsPlayer.cancel()
        }
        val remaining = pendingCount()
        if (remaining > 0) pendingReplyNotifier.notifyPending(remaining)
        else pendingReplyNotifier.cancel()

        // Preserve an unrelated in-flight playback and its transport state.
        // Only replace the status when this clear operation left no active item.
        if (!isPlaybackActive()) {
            _playbackStatus.value = if (remaining > 0) {
                PlaybackStatus.PendingCount(remaining)
            } else {
                PlaybackStatus.Idle
            }
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
        val queuedStart: Boolean? = queueDrainLock.withLock {
            val wasQueued = synchronized(pendingQueue) {
                val idx = pendingQueue.indexOfFirst { it.id == message.id }
                if (idx >= 0) {
                    pendingQueue.removeAt(idx)
                    true
                } else false
            }
            if (!wasQueued) return@withLock null
            // Hard-stop any in-flight playback so the requested message can
            // become the playing one, then keep the whole selection/start
            // transition under the same lock used by automatic drains.
            interruptForReplacement(message.id)
            pendingReplyNotifier.notifyPending(pendingCount())
            playQueued(message)
        }
        when (queuedStart) {
            null -> playManually(message)
            false -> playNext()
            true -> Unit
        }
    }

    companion object {
        /**
         * v1.23.0-rc.6 — beat between the mic releasing and the auto-play
         * drain kicking in. 2 s is long enough to feel like a deliberate
         * gap (so the queued voice note doesn't step on the user's
         * context-switch), short enough that the user doesn't think the
         * queue is stuck.
         */
        private const val POST_RECORDING_DRAIN_DELAY_MS: Long = 2_000L

        /**
         * Bounded depth of [playedHistory]. Five steps back covers the
         * "I just listened past the message I cared about" case; deeper
         * history is wasted memory because nobody scrolls back further
         * than that on a transport control.
         */
        private const val PLAYED_HISTORY_CAP: Int = 5
    }

    private fun isUserRecordingOrStarting(): Boolean =
        userRecordingGateActive || audioRecorder.isRecording()

    /** @return true when playback started or was safely deferred. */
    private suspend fun playQueued(message: Message): Boolean {
        val settings = settingsRepository.get()
        // A fresh incoming/manual playback may have started while the settings
        // read suspended. Put this item back at the front instead of allowing
        // the queued path (which bypasses user gates) to replace it.
        if (isPlaybackActive()) {
            synchronized(pendingQueue) {
                if (pendingQueue.none { it.id == message.id }) pendingQueue.add(0, message)
            }
            pendingReplyNotifier.notifyPending(pendingCount())
            return true
        }
        val hasReadableSummary = !message.summary.isNullOrBlank() ||
            !message.transcription.isNullOrBlank() ||
            !message.textContent.isNullOrBlank()
        return if (settings.summarizeLongMessages &&
            settings.summaryEngine != SummaryEngineType.NONE &&
            hasReadableSummary
        ) {
            playSummaryOrTranscription(message, respectUserGates = false)
            true
        } else if (!message.audioFilePath.isNullOrBlank()) {
            playVoiceMessage(message, respectUserGates = false)
            true
        } else if (!message.textContent.isNullOrBlank()) {
            playTextMessage(message, respectUserGates = false)
            true
        } else if (hasReadableSummary) {
            // A summary/transcription may be the only surviving representation
            // after cache cleanup; it is still preferable to silent loss.
            playSummaryOrTranscription(message, respectUserGates = false)
            true
        } else {
            handlePlaybackFailure(
                message,
                IllegalStateException("No playable audio or text content")
            )
            false
        }
    }
}
