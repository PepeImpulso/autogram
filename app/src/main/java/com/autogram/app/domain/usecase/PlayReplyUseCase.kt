package com.autogram.app.domain.usecase

import com.autogram.app.domain.model.*
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.infrastructure.audio.AudioPlayer
import com.autogram.app.infrastructure.persistence.dao.PendingPlaybackDao
import com.autogram.app.infrastructure.persistence.entity.PendingPlaybackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.Collections
import javax.inject.Inject

/**
 * Caso de uso: reproducir automáticamente las respuestas entrantes.
 * Gestiona la cola de reproducción y el audio focus.
 */
class PlayReplyUseCase @Inject constructor(
    private val audioPlayer: AudioPlayer,
    private val messageRepository: MessageRepository,
    private val settingsRepository: SettingsRepository,
    private val pendingPlaybackDao: PendingPlaybackDao  // BUG-05: Persist pending queue
) {
    private val _playbackStatus = MutableStateFlow<PlaybackStatus>(PlaybackStatus.Idle)
    val playbackStatus: StateFlow<PlaybackStatus> = _playbackStatus

    // BUG-43 FIX: Thread-safe list for concurrent access from multiple coroutines
    private val pendingQueue = Collections.synchronizedList(mutableListOf<Message>())
    private var currentPlayingId: String? = null
    // BUG-12: Dedicated scope for MediaPlayer callbacks instead of runBlocking
    private val playbackScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

        val result = audioPlayer.play(audioFile) {
            // BUG-12: Use CoroutineScope instead of runBlocking in MediaPlayer callback
            playbackScope.launch {
                messageRepository.update(message.copy(
                    state = MessageState.PLAYED,
                    playedAt = System.currentTimeMillis()
                ))
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
     * Reproduce un mensaje de texto usando TTS (para futuras versiones).
     */
    fun playTextMessage(message: Message) {
        // TODO: Implementar TTS para mensajes de texto en v1.1
        Timber.d("TTS playback no implementado aún para: ${message.textContent}")
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
        // BUG-44 FIX: Cancel playback scope to prevent leaks
        playbackScope.cancel()
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
        val next = synchronized(pendingQueue) {
            if (pendingQueue.isEmpty()) {
                _playbackStatus.value = PlaybackStatus.Idle
                return
            }
            pendingQueue.removeAt(0)
        }
        // BUG-42 FIX: Delete only the specific playback for this message, not all
        playbackScope.launch {
            try { pendingPlaybackDao.delete(next.id) } catch (_: Exception) {}
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
