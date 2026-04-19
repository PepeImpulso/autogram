package com.autogram.app.infrastructure.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Reproductor de audio para mensajes de voz recibidos.
 * Usa MediaPlayer con atributos de CONTENT_TYPE_SPEECH.
 */
class AudioPlayer @Inject constructor(
    private val context: Context
) {
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var completionHandler: (() -> Unit)? = null

    /**
     * Reproduce un archivo de audio.
     * Solicita audio focus temporal antes de reproducir.
     * @param file Archivo de audio OGG
     * @param onComplete Callback invocado al finalizar la reproducción
     */
    fun play(file: File, onComplete: () -> Unit = {}): Result<Unit> {
        return try {
            stop()
            completionHandler = onComplete

            // Solicitar audio focus
            if (!requestAudioFocus()) {
                Timber.w("Audio focus denegado")
                return Result.failure(Exception("Audio focus denegado"))
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setOnCompletionListener {
                    isPlaying = false
                    abandonAudioFocus()
                    completionHandler?.invoke()
                }
                setOnErrorListener { _, what, extra ->
                    Timber.e("MediaPlayer error: what=$what extra=$extra")
                    isPlaying = false
                    abandonAudioFocus()
                    completionHandler?.invoke()
                    true
                }
                prepare()
                start()
            }
            isPlaying = true
            Timber.d("Reproduciendo: ${file.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error al reproducir audio")
            abandonAudioFocus()
            Result.failure(e)
        }
    }

    fun pause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                isPlaying = false
            }
        }
    }

    fun resume() {
        try {
            mediaPlayer?.let {
                it.start()
                isPlaying = true
            }
        } catch (e: Exception) {
            Timber.e(e, "Error al reanudar reproducción")
        }
    }

    fun stop() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {
        }
        mediaPlayer = null
        isPlaying = false
        abandonAudioFocus()
    }

    fun isPlaying(): Boolean = isPlaying

    /**
     * Solicita audio focus temporal para reproducción de voz.
     */
    private fun requestAudioFocus(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    if (focusChange == android.media.AudioManager.AUDIOFOCUS_LOSS) {
                        pause()
                    }
                }
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { focusChange ->
                    if (focusChange == android.media.AudioManager.AUDIOFOCUS_LOSS) {
                        pause()
                    }
                },
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            ) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
        }
        audioFocusRequest = null
    }
}
