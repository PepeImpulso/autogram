package com.autogram.app.infrastructure.audio

import android.annotation.SuppressLint
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
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val outputSnapshot = getOutputDevices(audioManager)
            val hasProjectedCarOutput = outputSnapshot.any { isProjectedCarOutputType(it.type) }
            val playbackAttributes = buildPlaybackAttributes(useNavigationGuidance = hasProjectedCarOutput)

            // Request audio focus. In an Android Auto session Google Maps /
            // the AA host will typically already hold focus for navigation
            // prompts, so AUDIOFOCUS_GAIN_TRANSIENT gets denied outright.
            // We accept that and keep playing anyway — the platform's ducking
            // layer mixes our playback with whatever else is active instead
            // of muting us into silence like the old hard-fail did.
            val focusGranted = requestAudioFocus(audioManager, playbackAttributes)
            if (!focusGranted) {
                Timber.w("Audio focus denied (likely AA/Maps active) — playing anyway with ducking")
            } else {
                Timber.d("Audio focus granted")
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setAudioAttributes(playbackAttributes)
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
                preferOutputRoute(this, audioManager, playbackAttributes, outputSnapshot)
                start()
                logRoutedDevice(this)
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
    private fun requestAudioFocus(
        audioManager: AudioManager,
        attributes: AudioAttributes
    ): Boolean {
        val request = try {
            AudioFocusRequest.Builder(
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener { focusChange ->
                    if (focusChange == android.media.AudioManager.AUDIOFOCUS_LOSS) {
                        pause()
                    }
                }
                .build()
        } catch (e: Exception) {
            Timber.w(e, "AudioFocusRequest build failed; treating as denied")
            return false
        }
        audioFocusRequest = request
        return audioManager.requestAudioFocus(request) ==
            android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /**
     * Route MediaPlayer output through the output Android is likely to use
     * for these attributes. When AA exposes a USB/BUS/HDMI car sink, prefer
     * that explicitly; the previous "car output exists, let the OS decide"
     * strategy still let some hosts pick a silent paired-BT route.
     *
     * The priority is: routed car output -> available car output -> routed
     * headset/BT -> available headset/BT -> OS default.
     */
    private fun preferOutputRoute(
        player: MediaPlayer,
        audioManager: AudioManager,
        attributes: AudioAttributes,
        outputSnapshot: List<AudioDeviceInfo>
    ) {
        try {
            val routedDevices = getRoutedDevices(audioManager, attributes)
            val preferredType = choosePreferredOutputType(
                routedTypes = routedDevices.map { it.type },
                outputTypes = outputSnapshot.map { it.type }
            )
            if (preferredType == null) {
                Timber.d(
                    "AudioPlayer routing: OS default (routed=${routedDevices.typeSummary()}, " +
                        "outputs=${outputSnapshot.typeSummary()})"
                )
                return
            }
            val preferred = (routedDevices + outputSnapshot)
                .distinctBy { it.id }
                .firstOrNull { it.type == preferredType }
            if (preferred == null) {
                Timber.d("AudioPlayer routing: preferred type $preferredType disappeared")
                return
            }
            val accepted = player.setPreferredDevice(preferred)
            Timber.i(
                "AudioPlayer preferred output type=${preferred.type} accepted=$accepted " +
                    "(routed=${routedDevices.typeSummary()}, outputs=${outputSnapshot.typeSummary()})"
            )
        } catch (e: SecurityException) {
            // BLUETOOTH_CONNECT not granted on API 31+ yet — fall back to OS default.
            Timber.d("Preferred audio route unavailable (permission): ${e.message}")
        } catch (e: Exception) {
            Timber.w(e, "preferOutputRoute failed; falling back to default route")
        }
    }

    private fun buildPlaybackAttributes(useNavigationGuidance: Boolean): AudioAttributes {
        return AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(
                if (useNavigationGuidance) {
                    AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                } else {
                    AudioAttributes.USAGE_MEDIA
                }
            )
            .build()
    }

    private fun getOutputDevices(audioManager: AudioManager): List<AudioDeviceInfo> {
        return runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.onFailure {
            Timber.d(it, "Audio output device scan failed")
        }.getOrDefault(emptyList())
    }

    private fun getRoutedDevices(
        audioManager: AudioManager,
        attributes: AudioAttributes
    ): List<AudioDeviceInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return emptyList()
        return runCatching {
            audioManager.getAudioDevicesForAttributes(attributes)
        }.onFailure {
            Timber.d(it, "Audio routed-device scan failed")
        }.getOrDefault(emptyList())
    }

    private fun logRoutedDevice(player: MediaPlayer) {
        runCatching {
            Timber.i("AudioPlayer started on routed output type=${player.routedDevice?.type}")
        }
    }

    private fun List<AudioDeviceInfo>.typeSummary(): String =
        joinToString(prefix = "[", postfix = "]") { it.type.toString() }

    // BUG-38: minSdk=29, always API O+
    private fun abandonAudioFocus() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } catch (_: Exception) {
        }
        audioFocusRequest = null
    }

    @SuppressLint("InlinedApi")
    companion object {
        private val PROJECTED_CAR_OUTPUT_PRIORITY = listOf(
            AudioDeviceInfo.TYPE_BUS,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC
        )

        private val CAR_OUTPUT_PRIORITY = PROJECTED_CAR_OUTPUT_PRIORITY +
            AudioDeviceInfo.TYPE_USB_HEADSET

        private val PERSONAL_OUTPUT_PRIORITY = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        )

        internal fun choosePreferredOutputType(
            routedTypes: List<Int>,
            outputTypes: List<Int>
        ): Int? {
            return firstByPriority(routedTypes, CAR_OUTPUT_PRIORITY)
                ?: firstByPriority(outputTypes, CAR_OUTPUT_PRIORITY)
                ?: firstByPriority(routedTypes, PERSONAL_OUTPUT_PRIORITY)
                ?: firstByPriority(outputTypes, PERSONAL_OUTPUT_PRIORITY)
        }

        internal fun isProjectedCarOutputType(type: Int): Boolean =
            type in PROJECTED_CAR_OUTPUT_PRIORITY

        private fun firstByPriority(types: List<Int>, priority: List<Int>): Int? =
            priority.firstOrNull { it in types }
    }
}
