package com.autogram.app.infrastructure.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Reproductor de audio para mensajes de voz recibidos.
 * Usa MediaPlayer con atributos de CONTENT_TYPE_SPEECH.
 */
class AudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var completionHandler: ((Result<Unit>) -> Unit)? = null

    /**
     * Reproduce un archivo de audio.
     * Solicita audio focus temporal antes de reproducir.
     * @param file Archivo de audio OGG
     * @param onComplete Callback invocado al finalizar la reproducción
     */
    // BUG-51 FIX: Callback now receives Result so caller can distinguish success from error
    fun play(
        file: File,
        speed: Float = 1f,
        onComplete: (Result<Unit>) -> Unit = {}
    ): Result<Unit> {
        return try {
            stop()
            completionHandler = onComplete

            // Request audio focus. In an Android Auto session Google Maps /
            // the AA host will typically already hold focus for navigation
            // prompts, so AUDIOFOCUS_GAIN_TRANSIENT gets denied outright.
            // We accept that and keep playing anyway — the platform's ducking
            // layer mixes our playback with whatever else is active instead
            // of muting us into silence like the old hard-fail did.
            val focusGranted = requestAudioFocus()
            if (!focusGranted) {
                Timber.w("Audio focus denied (likely AA/Maps active) — playing anyway with ducking")
            } else {
                Timber.d("Audio focus granted")
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
                    this@AudioPlayer.isPlaying = false
                    abandonAudioFocus()
                    completionHandler?.invoke(Result.success(Unit))
                }
                setOnErrorListener { _, what, extra ->
                    Timber.e("MediaPlayer error: what=$what extra=$extra")
                    this@AudioPlayer.isPlaying = false
                    abandonAudioFocus()
                    completionHandler?.invoke(Result.failure(Exception("MediaPlayer error: what=$what extra=$extra")))
                    true
                }
                prepare()
                // Applying PlaybackParams before start() on some OEMs silently
                // auto-starts playback; set it after prepare() + right before
                // start() and guard against invalid speeds.
                if (speed != 1f) {
                    runCatching {
                        val clamped = speed.coerceIn(0.25f, 4f)
                        playbackParams = playbackParams.setSpeed(clamped)
                    }.onFailure { Timber.w(it, "setSpeed($speed) failed — playing at 1x") }
                }
                // Prefer routing to a connected Bluetooth / car output so the
                // driver hears replies through the car speakers / headset
                // instead of the phone's loudspeaker.
                preferBluetoothRoute(this)
                start()
            }
            isPlaying = true
            Timber.d("Reproduciendo: ${file.name} (speed=${speed}x)")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error al reproducir audio")
            // BUG-17: Release MediaPlayer on error to prevent resource leak
            try { mediaPlayer?.release() } catch (_: Exception) {}
            mediaPlayer = null
            isPlaying = false
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
     * Request temporary audio focus for voice reply playback. Uses
     * AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK so the Android Auto host / Google
     * Maps navigation prompts are more likely to grant it — the "may duck"
     * hint tells the platform that other players can keep going at reduced
     * volume instead of having to stop, which AA permits freely where
     * full-exclusive TRANSIENT gets denied.
     *
     * AUDIOFOCUS_LOSS still pauses us (permanent loss means someone took
     * over, e.g. a phone call); transient losses are ignored so navigation
     * blurbs don't chop our message in half.
     */
    // BUG-38: minSdk=29, so we always have API O. Removed dead < O branches.
    private fun requestAudioFocus(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioFocusRequest = AudioFocusRequest.Builder(
            android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { focusChange ->
                if (focusChange == android.media.AudioManager.AUDIOFOCUS_LOSS) {
                    pause()
                }
            }
            .build()
        return audioManager.requestAudioFocus(audioFocusRequest!!) ==
            android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /**
     * Route MediaPlayer output through a connected Bluetooth A2DP (stereo) or
     * wired headset when available — otherwise fall back to whatever the OS
     * selects. Safe no-op on API < 28 where setPreferredDevice was added.
     */
    private fun preferBluetoothRoute(player: MediaPlayer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            // Priority: A2DP (stereo BT headset / car) → wired headset → SCO (voice BT).
            val preferred = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                ?: devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (preferred != null) {
                player.setPreferredDevice(preferred)
                Timber.d("AudioPlayer routing to ${preferred.productName} (type=${preferred.type})")
            }
        } catch (e: SecurityException) {
            // BLUETOOTH_CONNECT not granted on API 31+ yet — fall back to OS default.
            Timber.d("BT route unavailable (no permission): ${e.message}")
        } catch (e: Exception) {
            Timber.w(e, "preferBluetoothRoute failed; falling back to default route")
        }
    }

    // BUG-38: minSdk=29, always API O+
    private fun abandonAudioFocus() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } catch (_: Exception) {
        }
        audioFocusRequest = null
    }
}
