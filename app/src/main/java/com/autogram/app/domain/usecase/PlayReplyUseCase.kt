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

    private val pendingQueue = mutableListOf<Message>()
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
        pendingQueue.clear()
        currentPlayingId = null
        _playbackStatus.value = PlaybackStatus.Idle
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
        pendingQueue.add(message)
        // BUG-05: Persist to database
        pendingPlaybackDao.insert(PendingPlaybackEntity(
            id = playback.id,
            messageId = playback.messageId,
            enqueuedAt = playback.enqueuedAt,
            reason = playback.reason,
            playedAt = null
        ))
        _playbackStatus.value = PlaybackStatus.PendingCount(pendingQueue.size)
        Timber.d("Mensaje ${message.id} encolado. Razón: $reason. Pendientes: ${pendingQueue.size}")
    }

    /**
     * BUG-05: Restore pending queue from database after service restart.
     */
    suspend fun restorePendingQueue() {
        val entities = pendingPlaybackDao.getAll()
        for (entity in entities) {
            val message = messageRepository.getById(entity.messageId) ?: continue
            if (message.state == MessageState.PENDING_PLAYBACK && !pendingQueue.any { it.id == message.id }) {
                pendingQueue.add(message)
            }
        }
        if (pendingQueue.isNotEmpty()) {
            _playbackStatus.value = PlaybackStatus.PendingCount(pendingQueue.size)
            Timber.d("Restaurados ${pendingQueue.size} mensajes pendientes de reproducción")
        }
    }

    /**
     * Reproduce el siguiente mensaje en cola.
     */
    private suspend fun playNext() {
        if (pendingQueue.isEmpty()) {
            _playbackStatus.value = PlaybackStatus.Idle
            return
        }
        val next = pendingQueue.removeAt(0)
        // BUG-05: Remove from DB when dequeued
        playbackScope.launch {
            try { pendingPlaybackDao.deleteAll() } catch (_: Exception) {}
        }
        playVoiceMessage(next)
    }

    /**
     * Reproduce todos los mensajes pendientes (cuando se desactiva el modo silencio o termina la llamada).
     */
    suspend fun playAllPending() {
        val messages = pendingQueue.toList()
        pendingQueue.clear()
        for (msg in messages) {
            playVoiceMessage(msg)
        }
    }

    /**
     * Devuelve el número de mensajes pendientes de reproducción.
     */
    fun pendingCount(): Int = pendingQueue.size
}
