package com.autogram.app.infrastructure.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gestor de audio focus para la aplicación.
 * Coordina la reproducción de audio con otros servicios multimedia del sistema.
 */
@Singleton
class AudioFocusManager @Inject constructor(
    private val context: Context
) {
    enum class FocusState {
        NONE, GRANTED, LOST, LOST_TRANSIENT
    }

    private val _focusState = MutableStateFlow(FocusState.NONE)
    val focusState: StateFlow<FocusState> = _focusState

    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /**
     * Solicita audio focus temporal para reproducción de voz.
     * @return true si se concedió el focus
     */
    fun requestFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    _focusState.value = when (focusChange) {
                        AudioManager.AUDIOFOCUS_GAIN -> FocusState.GRANTED
                        AudioManager.AUDIOFOCUS_LOSS -> FocusState.LOST
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusState.LOST_TRANSIENT
                        else -> FocusState.NONE
                    }
                }
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { focusChange ->
                    _focusState.value = when (focusChange) {
                        AudioManager.AUDIOFOCUS_GAIN -> FocusState.GRANTED
                        AudioManager.AUDIOFOCUS_LOSS -> FocusState.LOST
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusState.LOST_TRANSIENT
                        else -> FocusState.NONE
                    }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        _focusState.value = if (result) FocusState.GRANTED else FocusState.LOST
        Timber.d("Audio focus solicitado: ${if (result) "concedido" else "denegado"}")
        return result
    }

    /**
     * Libera el audio focus.
     */
    fun abandonFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
        }
        audioFocusRequest = null
        _focusState.value = FocusState.NONE
    }

    fun hasFocus(): Boolean = _focusState.value == FocusState.GRANTED
}
