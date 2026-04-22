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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    // BUG-12: Dedicated scope for MediaPlayer callbacks instead of runBlocking
    // BUG-50 FIX: Mutable scope so it can be recreated after stopAll()
    private var playbackScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
    suspend fun playVoiceMessage(message: Message, isCallActive: Boolean = false) {
        if (isCallActive) {
            enqueuePending(message, PendingReason.IN_CALL)
            return
        }

        val settings = settingsRepository.get()
        if (settings.silentMode) {
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
            // BUG-12: Use CoroutineScope instead of runBlocking in MediaPlayer callback
            // BUG-51 FIX: Check result to mark PLAYED or FAILED correctly
            playbackScope.launch {
                if (playResult.isSuccess) {
                    val played = message.copy(
                        state = MessageState.PLAYED,
                        playedAt = System.currentTimeMillis(),
                        // If auto-delete is on, drop the file pointer too so we
                        // don't show a stale path in History.
                        audioFilePath = if (settings.autoDeletePlayed) null else message.audioFilePath
                    )
                    messageRepository.update(played)
                    if (settings.autoDeletePlayed) {
                        runCatching { audioFile.delete() }
                            .onFailure { Timber.w(it, "Failed to delete played audio ${audioFile.absolutePath}") }
                    }
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
        if (audioPlayer.isPlaying()) audioPlayer.stop()
        val audioFile = message.audioFilePath?.let { File(it) }
        if (audioFile == null || !audioFile.exists()) {
            Timber.w("playManually: missing audio for ${message.id}")
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
    suspend fun playSummaryOrTranscription(message: Message, isCallActive: Boolean = false) {
        val text = message.summary?.takeIf { it.isNotBlank() }
            ?: message.transcription?.takeIf { it.isNotBlank() }
            ?: message.textContent?.takeIf { it.isNotBlank() }
            ?: return
        val settings = settingsRepository.get()
        if (isCallActive) {
            enqueuePending(message, PendingReason.IN_CALL); return
        }
        if (settings.silentMode) {
            enqueuePending(message, PendingReason.SILENT_MODE); return
        }
        if (!settings.autoPlayReplies) return

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

    suspend fun playTextMessage(message: Message, isCallActive: Boolean = false) {
        val text = message.textContent ?: return
        if (text.isBlank()) return
        // Same gates as playVoiceMessage: in a call, silent mode, or autoplay
        // off — queue for later instead of speaking over other audio. Texts
        // share the same pending queue since they're Messages too, so
        // playAllPending()/playNext() already handle them via playVoiceMessage
        // but text rows have no audioFilePath → fall through to TTS there.
        val settings = settingsRepository.get()
        if (isCallActive) {
            enqueuePending(message, PendingReason.IN_CALL)
            return
        }
        if (settings.silentMode) {
            enqueuePending(message, PendingReason.SILENT_MODE)
            return
        }
        if (!settings.autoPlayReplies) {
            // User opted out of auto-play. Leave the row RECEIVED so the
            // History screen shows it as unread; they'll tap play themselves.
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
            if (message.state == MessageState.PENDING_PLAYBACK && !pendingQueue.any { it.id == message.id }) {
                synchronized(pendingQueue) { pendingQueue.add(message) }
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
        if (next.audioFilePath != null) {
            playVoiceMessage(next)
        } else if (!next.textContent.isNullOrBlank()) {
            playTextMessage(next)
        }
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
        if (first.audioFilePath != null) {
            playVoiceMessage(first)
        } else if (!first.textContent.isNullOrBlank()) {
            playTextMessage(first)
        }
    }

    /**
     * Devuelve el número de mensajes pendientes de reproducción.
     */
    fun pendingCount(): Int = synchronized(pendingQueue) { pendingQueue.size }
}
