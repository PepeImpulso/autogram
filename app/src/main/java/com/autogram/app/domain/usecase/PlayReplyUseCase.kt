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
    private var currentPlayingId: String? = null
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

        if (audioPlayer.isPlaying()) {
            // Encolar para reproducción secuencial
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
            playNext()
        }
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
    suspend fun playTextMessage(message: Message) {
        val text = message.textContent ?: return
        if (text.isBlank()) return
        currentPlayingId = message.id
        messageRepository.update(message.copy(state = MessageState.PLAYING))
        _playbackStatus.value = PlaybackStatus.Playing(message.id)
        ttsPlayer.speak(text) {
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
            try { pendingPlaybackDao.deleteByMessageId(next.id) } catch (_: Exception) {}
        }
        playVoiceMessage(next)
    }

    /**
     * Reproduce todos los mensajes pendientes (cuando se desactiva el modo silencio o termina la llamada).
     */
    suspend fun playAllPending() {
        val messages = synchronized(pendingQueue) { pendingQueue.toList() }
        synchronized(pendingQueue) { pendingQueue.clear() }
        for (msg in messages) {
            playVoiceMessage(msg)
        }
    }

    /**
     * Devuelve el número de mensajes pendientes de reproducción.
     */
    fun pendingCount(): Int = synchronized(pendingQueue) { pendingQueue.size }
}
