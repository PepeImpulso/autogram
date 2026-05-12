package com.autogram.app.infrastructure.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import com.autogram.app.domain.model.AudioFocusMode
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audio player for incoming voice messages. Uses MediaPlayer with
 * AudioAttributes(USAGE_MEDIA, CONTENT_TYPE_SPEECH). Audio focus strategy is
 * caller-controlled (see [AudioFocusMode]) so the user can flip modes in
 * Settings when diagnosing AA audio-routing issues.
 *
 * Marked @Singleton so the routed-device StateFlow has a stable subscriber
 * surface for the phone Settings diagnostics card.
 */
@Singleton
class AudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagnostics: PlaybackDiagnostics
) {
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var completionHandler: ((Result<Unit>) -> Unit)? = null
    // v1.24.1 — track focus result + focus mode at play() time so the
    // post-start diagnostics record carries both. Kept private; the
    // public StateFlow surface lives on [PlaybackDiagnostics].
    private var lastFocusGranted: Boolean = false
    private var lastFocusMode: AudioFocusMode = AudioFocusMode.TRANSIENT_MAY_DUCK

    /**
     * Play an audio file. Requests audio focus per [focusMode] before
     * starting playback. The mode is caller-controlled (read from
     * AppSettings.audioFocusMode by PlayReplyUseCase) so the user can
     * cycle through modes from phone Settings when AA audio-routing
     * doesn't land on the car speakers.
     * @param file OGG audio file
     * @param speed Playback speed multiplier (1.0 = unchanged)
     * @param focusMode How to request audio focus — see [AudioFocusMode]
     * @param onComplete Callback invoked when playback finishes or errors
     */
    fun play(
        file: File,
        speed: Float = 1f,
        focusMode: AudioFocusMode = AudioFocusMode.TRANSIENT_MAY_DUCK,
        onComplete: (Result<Unit>) -> Unit = {}
    ): Result<Unit> {
        return try {
            stop()
            completionHandler = onComplete
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val outputSnapshot = getOutputDevices(audioManager)
            val playbackAttributes = buildPlaybackAttributes()

            // Request audio focus. In an Android Auto session Google Maps /
            // the AA host typically already holds focus for navigation prompts.
            // TRANSIENT_MAY_DUCK is accepted most freely but some cars treat
            // it as a "soft" request and route the audio to a muted channel.
            // TRANSIENT_EXCLUSIVE / GAIN are stricter (other media is ducked
            // or paused). Whichever the user has selected, we keep playing
            // even on denial — the platform's mixer is responsible for the
            // final output. See [AudioFocusMode].
            val focusGranted = requestAudioFocus(audioManager, playbackAttributes, focusMode)
            lastFocusGranted = focusGranted
            lastFocusMode = focusMode
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
            Timber.d("Playing: ${file.name} (speed=${speed}x)")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to play audio")
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
            Timber.e(e, "Failed to resume playback")
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
     * Current playback position in milliseconds, or null when nothing is
     * loaded. Used by the Pending Queue's Now Playing scrubber. The
     * underlying MediaPlayer.getCurrentPosition() can throw
     * IllegalStateException if the player is in an Error state, so we
     * wrap it.
     */
    fun currentPositionMs(): Int? = runCatching { mediaPlayer?.currentPosition }.getOrNull()

    /**
     * Total duration of the loaded track in milliseconds, or null when
     * nothing is loaded. Same IllegalStateException risk as
     * [currentPositionMs].
     */
    fun durationMs(): Int? = runCatching { mediaPlayer?.duration }.getOrNull()

    /**
     * v1.23.0-rc.6 — jumps the underlying [mediaPlayer] to [positionMs]
     * (clamped to [0, duration]). No-op if nothing is loaded or the
     * MediaPlayer is in an Error state. Drives the tap-to-seek interaction
     * on the Pending Queue's Now Playing waveform and on AA's progress bar
     * via [MediaSessionCompat.Callback.onSeekTo].
     */
    fun seekTo(positionMs: Int) {
        runCatching {
            val mp = mediaPlayer ?: return
            val clamped = positionMs.coerceAtLeast(0).let { p ->
                val d = mp.duration
                if (d > 0) p.coerceAtMost(d) else p
            }
            mp.seekTo(clamped)
        }
    }

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
        attributes: AudioAttributes,
        focusMode: AudioFocusMode
    ): Boolean {
        val focusGainType = when (focusMode) {
            AudioFocusMode.TRANSIENT_MAY_DUCK -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioFocusMode.TRANSIENT_EXCLUSIVE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioFocusMode.GAIN -> AudioManager.AUDIOFOCUS_GAIN
        }
        val request = try {
            AudioFocusRequest.Builder(focusGainType)
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
     * Route MediaPlayer output through a connected personal output when no
     * projected Android Auto route is present. During USB Android Auto, the
     * car can expose BUS/USB/HDMI sinks that are controlled by Gearhead; forcing
     * `setPreferredDevice()` to those sinks has proven to route playback into
     * silence on some cars. In that state, leave routing to the OS.
     */
    private fun preferOutputRoute(
        player: MediaPlayer,
        audioManager: AudioManager,
        attributes: AudioAttributes,
        outputSnapshot: List<AudioDeviceInfo>
    ) {
        try {
            val routedDevices = getRoutedDevices(audioManager, attributes)
            // Routing logic when an Android Auto sink (BUS / USB / HDMI)
            // is on the system's output list:
            //
            // Pre-v1.20.21 we forced playback to the phone's built-in
            // speaker as a last resort so the user "always heard
            // something" — but that fired the phone-speaker even while
            // the user was driving with the phone in a pocket /
            // cupholder, which is the worst possible spot for audio.
            // Reported as "audio doesn't come out of the car speakers"
            // even though the messages were technically playing.
            //
            // New behaviour: if a personal output that routes to the
            // car is available (Bluetooth A2DP, wired headset, etc.),
            // use it — that's what carries audio to the car's audio
            // system in most setups. If NO such personal output is
            // present (USB-AA only, no BT pair) we leave routing to
            // the OS instead of forcing the phone speaker. The OS may
            // surface our playback through AA's media stream, which
            // works on modern Gearhead even when we're not the active
            // media app; if it doesn't, silence is no worse than an
            // inaudible phone speaker buzzing in a pocket. BT/wired
            // users hear the message through the car regardless.
            val carPresent = (routedDevices + outputSnapshot)
                .any { isProjectedCarOutputType(it.type) }
            if (carPresent) {
                // v1.23.2 — leave routing to the OS when a projected AA
                // sink is present. The previous logic forced BT A2DP /
                // wired headset preferences here on the assumption that
                // those personal outputs would reach the car's audio
                // system. Real-world finding (Pepe, Galaxy + USB AA + BT
                // pair to the same car): the car treats BT A2DP and USB
                // AA media as separate sources and only renders one at a
                // time. With USB AA active, BT A2DP is muted at the car
                // — so forcing setPreferredDevice(BT A2DP) here sent the
                // audio to a silenced sink. The user reproduced this by
                // having to disable phone Bluetooth to get audio through
                // the car speakers; toggling BT removed BT A2DP from
                // outputSnapshot, which made phoneDevice null, which
                // skipped setPreferredDevice, which let the OS route
                // through AA's media stream and the audio came through.
                //
                // The MediaSession + AudioAttributes(USAGE_MEDIA,
                // CONTENT_TYPE_SPEECH) we already set are what AA needs
                // to surface our playback in its media stream; no
                // setPreferredDevice call is required for that path.
                Timber.i(
                    "AudioPlayer: AA connected — leaving routing to OS so AA's " +
                        "media stream picks up the playback " +
                        "(routed=${routedDevices.typeSummary()}, outputs=${outputSnapshot.typeSummary()})"
                )
                return
            }
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

    private fun buildPlaybackAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
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
            val type = player.routedDevice?.type
            diagnostics.record(
                source = PlaybackDiagnostics.Source.AUDIO_PLAYER,
                focusGranted = lastFocusGranted,
                routedDeviceType = type,
                focusMode = lastFocusMode
            )
            Timber.i(
                "AudioPlayer started on routed output type=$type " +
                    "(focusGranted=$lastFocusGranted mode=$lastFocusMode)"
            )
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

        // Personal outputs ranked in the order we want to fall back to
        // when a projected car sink is present. BLUETOOTH_A2DP and
        // wired headsets are the most "personal" (user's own ears) AND
        // route to the car's audio system in most car setups, which is
        // what we actually want when AA is connected. v1.20.21 dropped
        // TYPE_BUILTIN_SPEAKER from this list — falling back to the
        // phone speaker while driving was useless (phone in pocket) and
        // just produced the "audio didn't play" reports. If no entry
        // here is available we now leave routing to the OS instead.
        private val PHONE_FALLBACK_PRIORITY = PERSONAL_OUTPUT_PRIORITY

        internal fun choosePreferredOutputType(
            routedTypes: List<Int>,
            outputTypes: List<Int>
        ): Int? {
            if ((routedTypes + outputTypes).any { isProjectedCarOutputType(it) }) return null
            return firstByPriority(routedTypes, PERSONAL_OUTPUT_PRIORITY)
                ?: firstByPriority(outputTypes, PERSONAL_OUTPUT_PRIORITY)
        }

        internal fun isProjectedCarOutputType(type: Int): Boolean =
            type in PROJECTED_CAR_OUTPUT_PRIORITY

        private fun firstByPriority(types: List<Int>, priority: List<Int>): Int? =
            priority.firstOrNull { it in types }
    }

    /**
     * Pick a non-projected, non-built-in-speaker output (Bluetooth
     * A2DP, wired headset, etc.) when a projected car sink is present.
     * Returning null is allowed and means "no good route — let the OS
     * decide." Pre-v1.20.21 we returned BUILTIN_SPEAKER as a last
     * resort; with AA active that just produced inaudible playback
     * from the user's pocketed phone, so we no longer fall back to it.
     */
    private fun pickPhonePersonalOutput(
        outputs: List<AudioDeviceInfo>,
        routed: List<AudioDeviceInfo>
    ): AudioDeviceInfo? {
        val combined = (routed + outputs).distinctBy { it.id }
        val personal = combined.filter {
            !isProjectedCarOutputType(it.type) &&
                it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        if (personal.isEmpty()) return null
        val byType = personal.groupBy { it.type }
        for (type in PHONE_FALLBACK_PRIORITY) {
            byType[type]?.firstOrNull()?.let { return it }
        }
        return personal.firstOrNull()
    }
}
