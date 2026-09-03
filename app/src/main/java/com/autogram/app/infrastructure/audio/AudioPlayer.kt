package com.autogram.app.infrastructure.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.os.Build
import androidx.car.app.connection.CarConnection
import com.autogram.app.domain.model.AudioFocusMode
import com.autogram.app.infrastructure.platform.CarConnectionMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audio player for incoming voice messages. Uses MediaPlayer with speech
 * audio attributes, promoting to the navigation-prompt usage while Android
 * Auto is connected so voice files follow the same reliable car route as TTS.
 * Audio focus strategy is caller-controlled (see [AudioFocusMode]) so the
 * user can flip modes in Settings when diagnosing AA audio-routing issues.
 *
 * Marked @Singleton so the routed-device StateFlow has a stable subscriber
 * surface for the phone Settings diagnostics card.
 */
@Singleton
class AudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagnostics: PlaybackDiagnostics,
    private val carConnectionMonitor: CarConnectionMonitor
) {
    private val operationLock = Any()
    private val stateLock = Any()
    private var mediaPlayer: MediaPlayer? = null
    @Volatile private var isPlaying = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var completionHandler: ((Result<Unit>) -> Unit)? = null
    private var playbackGeneration: Long = 0L

    /**
     * Play an audio file. Requests audio focus per [focusMode] before
     * starting playback. The mode is caller-controlled (read from
     * AppSettings.audioFocusMode by PlayReplyUseCase) so the user can
     * cycle through modes from phone Settings when AA audio-routing
     * doesn't land on the car speakers.
     * @param file OGG audio file
     * @param speed Playback speed multiplier (1.0 = unchanged)
     * @param focusMode How to request audio focus — see [AudioFocusMode]
     * @param useNavigationUsage v1.24.5 — when true, route playback through
     *   `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` (the Google Maps directions
     *   path) instead of `USAGE_MEDIA`. Mirrors `TtsPlayer.speak`'s
     *   `ttsAsNavigationPrompt` branch; used by `PlayReplyUseCase` so the
     *   single user-facing toggle covers both engines. Without this, voice
     *   message replies were stuck on `USAGE_MEDIA` while TTS already had
     *   the nav-path escape hatch — manifested as "text replies play in
     *   the car but voice replies don't" on Pepe's Seat Ateca.
     * @param onComplete Callback invoked when playback finishes or errors
     */
    fun play(
        file: File,
        speed: Float = 1f,
        focusMode: AudioFocusMode = AudioFocusMode.TRANSIENT_MAY_DUCK,
        useNavigationUsage: Boolean = false,
        onComplete: (Result<Unit>) -> Unit = {}
    ): Result<Unit> = synchronized(operationLock) {
        playLocked(file, speed, focusMode, useNavigationUsage, onComplete)
    }

    private fun playLocked(
        file: File,
        speed: Float,
        focusMode: AudioFocusMode,
        useNavigationUsage: Boolean,
        onComplete: (Result<Unit>) -> Unit
    ): Result<Unit> {
        var startedGeneration: Long? = null
        return try {
            // v1.24.6 — entry log up-front so the file log makes it
            // obvious which playback path (and which attributes) was
            // actually used for an incoming voice reply. The previous
            // entry log only fired AFTER start() succeeded, which made
            // routing-silenced playback indistinguishable from "play()
            // never called" in the log file.
            val sourceFormat = inspectAudioFormat(file)
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val outputSnapshot = getOutputDevices(audioManager)
            val carConnectionType = carConnectionMonitor.currentType()
            val effectiveSpeed = chooseEffectivePlaybackSpeed(
                requestedSpeed = speed,
                carConnectionType = carConnectionType,
                outputTypes = outputSnapshot.map { it.type }
            )
            Timber.i(
                "AudioPlayer.play start file=${file.name} speed=$speed " +
                    "effectiveSpeed=$effectiveSpeed " +
                    "focusMode=$focusMode navUsage=$useNavigationUsage exists=${file.exists()} " +
                    "size=${file.length()} source=${sourceFormat?.logSummary() ?: "unavailable"}"
            )
            if (effectiveSpeed != PlaybackSpeed.coerce(speed)) {
                Timber.i(
                    "AudioPlayer: car playback uses 1x to avoid OEM time-stretch artifacts " +
                        "(requestedSpeed=$speed " +
                        "carConnection=${carConnectionMonitor.typeLabel(carConnectionType)} " +
                        "outputs=${outputSnapshot.typeSummary()})"
                )
            }
            stopLocked()
            val generation = beginPlayback(onComplete)
            startedGeneration = generation
            val effectiveNavigationUsage = shouldUseNavigationUsage(
                requestedNavigationUsage = useNavigationUsage,
                carConnectionType = carConnectionType,
                outputTypes = outputSnapshot.map { it.type }
            )
            val playbackAttributes = buildPlaybackAttributes(effectiveNavigationUsage)
            Timber.d(
                "AudioPlayer.play attrs " +
                    "usage=${playbackAttributes.usage} contentType=${playbackAttributes.contentType} " +
                    "requestedNavUsage=$useNavigationUsage effectiveNavUsage=$effectiveNavigationUsage " +
                    "carConnection=${carConnectionMonitor.typeLabel(carConnectionType)} " +
                    "outputs=${outputSnapshot.typeSummary()}"
            )

            // Request audio focus. In an Android Auto session Google Maps /
            // the AA host typically already holds focus for navigation prompts.
            // TRANSIENT_MAY_DUCK is accepted most freely but some cars treat
            // it as a "soft" request and route the audio to a muted channel.
            // TRANSIENT_EXCLUSIVE / GAIN are stricter (other media is ducked
            // or paused). A denied request is a failed start: allowing the
            // decoder to run anyway would mark a message played even when
            // Android Auto's mixer suppresses it.
            when (requestAudioFocus(audioManager, playbackAttributes, focusMode, generation)) {
                FocusRequestResult.DENIED -> {
                    Timber.w("Audio focus denied; voice playback will not start")
                    diagnostics.record(
                        source = PlaybackDiagnostics.Source.AUDIO_PLAYER,
                        focusGranted = false,
                        routedDeviceType = null,
                        focusMode = focusMode
                    )
                    cancelStart(generation)
                    return Result.failure(AudioFocusDeniedException("voice playback"))
                }
                FocusRequestResult.SUPERSEDED -> {
                    Timber.d("Audio focus request superseded before playback start")
                    return Result.success(Unit)
                }
                FocusRequestResult.GRANTED -> Timber.d("Audio focus granted")
            }

            val player = MediaPlayer()
            if (!attachPlayer(generation, player)) {
                player.release()
                return Result.success(Unit)
            }
            configureAndStartPlayer(
                generation = generation,
                player = player,
                file = file,
                playbackAttributes = playbackAttributes,
                audioManager = audioManager,
                outputSnapshot = outputSnapshot,
                carConnectionType = carConnectionType,
                focusMode = focusMode,
                speed = effectiveSpeed,
                attempt = 0,
                allowPreferredRoute = true
            )
            Timber.d("Playing: ${file.name} (speed=${effectiveSpeed}x)")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to play audio")
            // If focus loss or an explicit stop already completed/invalidated
            // this generation, its callback/state owner has handled the event.
            // Returning success prevents callers from processing it twice.
            val generation = startedGeneration
            if (generation == null || cancelStart(generation)) {
                Result.failure(e)
            } else {
                Result.success(Unit)
            }
        }
    }

    private fun configureAndStartPlayer(
        generation: Long,
        player: MediaPlayer,
        file: File,
        playbackAttributes: AudioAttributes,
        audioManager: AudioManager,
        outputSnapshot: List<AudioDeviceInfo>,
        carConnectionType: Int,
        focusMode: AudioFocusMode,
        speed: Float,
        attempt: Int,
        allowPreferredRoute: Boolean
    ) {
        player.setDataSource(file.absolutePath)
        player.setAudioAttributes(playbackAttributes)
        player.setOnCompletionListener {
            completePlayback(
                generation = generation,
                expectedPlayer = player,
                result = Result.success(Unit)
            )
        }
        player.setOnErrorListener { _, what, extra ->
            if (shouldRetryMediaPlayerError(what, extra, attempt)) {
                retryInvalidOperation(
                    generation = generation,
                    failedPlayer = player,
                    file = file,
                    playbackAttributes = playbackAttributes,
                    audioManager = audioManager,
                    outputSnapshot = outputSnapshot,
                    carConnectionType = carConnectionType,
                    focusMode = focusMode,
                    what = what,
                    extra = extra
                )
            } else {
                Timber.e(
                    "MediaPlayer error file=${file.name} attempt=$attempt " +
                        "what=$what extra=$extra"
                )
                completePlayback(
                    generation = generation,
                    expectedPlayer = player,
                    result = Result.failure(
                        Exception("MediaPlayer error: what=$what extra=$extra")
                    )
                )
            }
            true
        }
        player.prepare()
        if (!isCurrentPlayback(generation, player)) return
        // Select the route before PlaybackParams: setPlaybackParams can
        // auto-start from Prepared on some OEMs, so routing afterwards
        // can lose the beginning of the message to a stale sink. A retry
        // deliberately leaves routing to Android to remove this variable.
        if (allowPreferredRoute) {
            preferOutputRoute(
                player = player,
                audioManager = audioManager,
                attributes = playbackAttributes,
                outputSnapshot = outputSnapshot,
                carConnectionType = carConnectionType,
                fileName = file.name
            )
        }
        // Applying PlaybackParams before start() on some OEMs silently
        // auto-starts playback; set it after prepare() + right before
        // start() and guard against invalid speeds.
        if (speed != 1f) {
            runCatching {
                player.playbackParams = player.playbackParams.setSpeed(speed)
            }.onFailure { Timber.w(it, "setSpeed($speed) failed — playing at 1x") }
        }
        if (!isCurrentPlayback(generation, player)) return
        player.start()
        if (markStarted(generation, player)) {
            logRoutedDevice(player, focusMode, file.name)
        }
    }

    /**
     * Samsung's Android 16 MediaPlayer can report INVALID_OPERATION (-38)
     * asynchronously after preparation. Replace the failed engine once and
     * retry with the least surprising configuration: 1x speed and OS routing.
     */
    private fun retryInvalidOperation(
        generation: Long,
        failedPlayer: MediaPlayer,
        file: File,
        playbackAttributes: AudioAttributes,
        audioManager: AudioManager,
        outputSnapshot: List<AudioDeviceInfo>,
        carConnectionType: Int,
        focusMode: AudioFocusMode,
        what: Int,
        extra: Int
    ) = synchronized(operationLock) {
        val replacement = MediaPlayer()
        if (!replacePlayerForRetry(generation, failedPlayer, replacement)) {
            releasePlayer(replacement)
            return@synchronized
        }
        runCatching {
            failedPlayer.setOnCompletionListener(null)
            failedPlayer.setOnErrorListener(null)
        }
        releasePlayer(failedPlayer)
        Timber.w(
            "MediaPlayer invalid operation file=${file.name} what=$what extra=$extra; " +
                "retrying once at 1x with OS routing"
        )
        runCatching {
            configureAndStartPlayer(
                generation = generation,
                player = replacement,
                file = file,
                playbackAttributes = playbackAttributes,
                audioManager = audioManager,
                outputSnapshot = outputSnapshot,
                carConnectionType = carConnectionType,
                focusMode = focusMode,
                speed = 1f,
                attempt = 1,
                allowPreferredRoute = false
            )
        }.onFailure { error ->
            Timber.e(error, "MediaPlayer recovery failed for ${file.name}")
            completePlayback(
                generation = generation,
                expectedPlayer = replacement,
                result = Result.failure(error)
            )
        }
    }

    fun pause() {
        val player = synchronized(stateLock) { mediaPlayer }
        player?.let {
            runCatching {
                if (it.isPlaying) it.pause()
            }.onSuccess {
                synchronized(stateLock) {
                    if (mediaPlayer === player) isPlaying = false
                }
            }
        }
    }

    fun resume() {
        try {
            val player = synchronized(stateLock) { mediaPlayer }
            player?.let {
                it.start()
                synchronized(stateLock) {
                    if (mediaPlayer === player) isPlaying = true
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to resume playback")
        }
    }

    fun stop() = synchronized(operationLock) {
        stopLocked()
    }

    private fun stopLocked() {
        val player: MediaPlayer?
        val focusRequest: AudioFocusRequest?
        synchronized(stateLock) {
            playbackGeneration++
            player = mediaPlayer
            focusRequest = audioFocusRequest
            mediaPlayer = null
            audioFocusRequest = null
            completionHandler = null
            isPlaying = false
        }
        releasePlayer(player)
        abandonAudioFocus(focusRequest)
    }

    fun isPlaying(): Boolean = isPlaying

    /** True while a player is prepared/paused/playing and owns completion state. */
    fun isActive(): Boolean = synchronized(stateLock) {
        mediaPlayer != null && completionHandler != null
    }

    private fun beginPlayback(onComplete: (Result<Unit>) -> Unit): Long =
        synchronized(stateLock) {
            playbackGeneration++
            completionHandler = onComplete
            mediaPlayer = null
            audioFocusRequest = null
            isPlaying = false
            playbackGeneration
        }

    private fun attachPlayer(generation: Long, player: MediaPlayer): Boolean =
        synchronized(stateLock) {
            if (generation != playbackGeneration || completionHandler == null) {
                false
            } else {
                mediaPlayer = player
                true
            }
        }

    private fun isCurrentPlayback(generation: Long, player: MediaPlayer): Boolean =
        synchronized(stateLock) {
            generation == playbackGeneration &&
                mediaPlayer === player &&
                completionHandler != null
        }

    private fun replacePlayerForRetry(
        generation: Long,
        failedPlayer: MediaPlayer,
        replacement: MediaPlayer
    ): Boolean = synchronized(stateLock) {
        if (generation != playbackGeneration ||
            mediaPlayer !== failedPlayer ||
            completionHandler == null
        ) {
            false
        } else {
            mediaPlayer = replacement
            isPlaying = false
            true
        }
    }

    private fun markStarted(generation: Long, player: MediaPlayer): Boolean =
        synchronized(stateLock) {
            if (generation != playbackGeneration || mediaPlayer !== player || completionHandler == null) {
                false
            } else {
                isPlaying = true
                true
            }
        }

    /**
     * Complete one playback generation exactly once. MediaPlayer callbacks
     * capture both the generation and player instance, so a late event from a
     * released player cannot invoke the handler installed for a newer message.
     */
    private fun completePlayback(
        generation: Long,
        expectedPlayer: MediaPlayer?,
        result: Result<Unit>
    ): Boolean {
        val player: MediaPlayer?
        val focusRequest: AudioFocusRequest?
        val callback: ((Result<Unit>) -> Unit)?
        synchronized(stateLock) {
            if (generation != playbackGeneration || completionHandler == null) return false
            if (expectedPlayer != null && mediaPlayer !== expectedPlayer) return false
            playbackGeneration++
            player = mediaPlayer
            focusRequest = audioFocusRequest
            callback = completionHandler
            mediaPlayer = null
            audioFocusRequest = null
            completionHandler = null
            isPlaying = false
        }
        releasePlayer(player)
        abandonAudioFocus(focusRequest)
        runCatching { callback?.invoke(result) }
            .onFailure { Timber.e(it, "AudioPlayer completion callback failed") }
        return true
    }

    /** Clear a failed start without invoking its asynchronous callback. */
    private fun cancelStart(generation: Long): Boolean {
        val player: MediaPlayer?
        val focusRequest: AudioFocusRequest?
        synchronized(stateLock) {
            if (generation != playbackGeneration || completionHandler == null) return false
            playbackGeneration++
            player = mediaPlayer
            focusRequest = audioFocusRequest
            mediaPlayer = null
            audioFocusRequest = null
            completionHandler = null
            isPlaying = false
        }
        releasePlayer(player)
        abandonAudioFocus(focusRequest)
        return true
    }

    private fun releasePlayer(player: MediaPlayer?) {
        if (player == null) return
        runCatching { if (player.isPlaying) player.stop() }
        runCatching { player.release() }
    }

    /**
     * Current playback position in milliseconds, or null when nothing is
     * loaded. Used by the Pending Queue's Now Playing scrubber. The
     * underlying MediaPlayer.getCurrentPosition() can throw
     * IllegalStateException if the player is in an Error state, so we
     * wrap it.
     */
    fun currentPositionMs(): Int? = runCatching {
        synchronized(stateLock) { mediaPlayer }?.currentPosition
    }.getOrNull()

    /**
     * Total duration of the loaded track in milliseconds, or null when
     * nothing is loaded. Same IllegalStateException risk as
     * [currentPositionMs].
     */
    fun durationMs(): Int? = runCatching {
        synchronized(stateLock) { mediaPlayer }?.duration
    }.getOrNull()

    /**
     * v1.23.0-rc.6 — jumps the underlying [mediaPlayer] to [positionMs]
     * (clamped to [0, duration]). No-op if nothing is loaded or the
     * MediaPlayer is in an Error state. Drives the tap-to-seek interaction
     * on the Pending Queue's Now Playing waveform and on AA's progress bar
     * via [MediaSessionCompat.Callback.onSeekTo].
     */
    fun seekTo(positionMs: Int) {
        runCatching {
            val mp = synchronized(stateLock) { mediaPlayer } ?: return
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
        focusMode: AudioFocusMode,
        generation: Long
    ): FocusRequestResult {
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
                    when {
                        shouldTerminateForFocusChange(focusChange) -> {
                            Timber.w("Audio focus lost during voice playback (change=$focusChange)")
                            completePlayback(
                                generation = generation,
                                expectedPlayer = null,
                                result = Result.failure(
                                    AudioFocusLostException("voice playback", focusChange)
                                )
                            )
                        }
                        focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            // The user chose a mode that permits ducking. Keep
                            // playing; Android owns the temporary attenuation.
                            Timber.d("Audio focus duck requested; voice playback continues")
                        }
                        focusChange == AudioManager.AUDIOFOCUS_GAIN -> {
                            Timber.d("Audio focus gained for voice playback")
                        }
                    }
                }
                .build()
        } catch (e: Exception) {
            Timber.w(e, "AudioFocusRequest build failed; treating as denied")
            return FocusRequestResult.DENIED
        }
        val installed = synchronized(stateLock) {
            if (generation != playbackGeneration || completionHandler == null) {
                false
            } else {
                audioFocusRequest = request
                true
            }
        }
        if (!installed) return FocusRequestResult.SUPERSEDED

        val result = runCatching { audioManager.requestAudioFocus(request) }
            .onFailure { Timber.w(it, "Audio focus request failed") }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            val wasCurrent = synchronized(stateLock) {
                if (generation == playbackGeneration && audioFocusRequest === request) {
                    audioFocusRequest = null
                    true
                } else {
                    false
                }
            }
            abandonAudioFocus(request)
            return if (wasCurrent) FocusRequestResult.DENIED else FocusRequestResult.SUPERSEDED
        }

        val stillCurrent = synchronized(stateLock) {
            generation == playbackGeneration &&
                audioFocusRequest === request &&
                completionHandler != null
        }
        if (!stillCurrent) {
            abandonAudioFocus(request)
            return FocusRequestResult.SUPERSEDED
        }
        return FocusRequestResult.GRANTED
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
        outputSnapshot: List<AudioDeviceInfo>,
        carConnectionType: Int,
        fileName: String
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
            val carPresent = isConnectedCarConnectionType(carConnectionType) ||
                (routedDevices + outputSnapshot).any { isProjectedCarOutputType(it.type) }
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
                // The MediaSession + current AudioAttributes are what AA
                // needs to surface playback in its car-controlled stream; no
                // setPreferredDevice call is required for that path.
                Timber.i(
                    "AudioPlayer: AA connected — leaving routing to OS so AA's " +
                        "media stream picks up the playback " +
                        "(file=$fileName carConnection=${carConnectionMonitor.typeLabel(carConnectionType)}, " +
                        "routed=${routedDevices.typeSummary()}, outputs=${outputSnapshot.typeSummary()})"
                )
                return
            }
            val preferredType = choosePreferredOutputType(
                routedTypes = routedDevices.map { it.type },
                outputTypes = outputSnapshot.map { it.type },
                carConnectionType = carConnectionType
            )
            if (preferredType == null) {
                Timber.d(
                    "AudioPlayer routing: OS default (file=$fileName routed=${routedDevices.typeSummary()}, " +
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
                "AudioPlayer preferred output file=$fileName type=${preferred.type} accepted=$accepted " +
                    "(routed=${routedDevices.typeSummary()}, outputs=${outputSnapshot.typeSummary()})"
            )
        } catch (e: SecurityException) {
            // BLUETOOTH_CONNECT not granted on API 31+ yet — fall back to OS default.
            Timber.d("Preferred audio route unavailable (permission): ${e.message}")
        } catch (e: Exception) {
            Timber.w(e, "preferOutputRoute failed; falling back to default route")
        }
    }

    private fun buildPlaybackAttributes(useNavigationUsage: Boolean): AudioAttributes {
        // v1.24.5 — mirrors `TtsPlayer.speak`'s `ttsAsNavigationPrompt`
        // branch so the user-facing toggle covers BOTH engines. Same
        // USAGE_ASSISTANCE_NAVIGATION_GUIDANCE + CONTENT_TYPE_SPEECH
        // pairing Google Maps directions use; that path speaks over music
        // and routes reliably to car speakers when the default USAGE_MEDIA
        // path gets silently muted by AA's media arbiter (the symptom Pepe
        // saw on the Seat Ateca for voice replies but not text replies).
        if (useNavigationUsage) {
            return AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                // This is spoken content, not a click/beep. SPEECH also keeps
                // Android's focus policy from automatically ducking words.
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        }
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

    private fun logRoutedDevice(
        player: MediaPlayer,
        focusMode: AudioFocusMode,
        fileName: String
    ) {
        runCatching {
            val type = player.routedDevice?.type
            diagnostics.record(
                source = PlaybackDiagnostics.Source.AUDIO_PLAYER,
                focusGranted = true,
                routedDeviceType = type,
                focusMode = focusMode
            )
            Timber.i(
                "AudioPlayer started file=$fileName on routed output type=$type " +
                    "(focusGranted=true mode=$focusMode)"
            )
        }
    }

    /** Extracts decoder-visible metadata without logging message content or paths. */
    private fun inspectAudioFormat(file: File): SourceAudioFormat? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount)
                .asSequence()
                .map(extractor::getTrackFormat)
                .firstOrNull { format ->
                    format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                ?.let { format ->
                    val durationUs = format.longOrNull(MediaFormat.KEY_DURATION)
                    val containerBitrate = format.intOrNull(MediaFormat.KEY_BIT_RATE)
                    val estimatedBitrate = durationUs
                        ?.takeIf { it > 0L }
                        ?.let { duration ->
                            ((file.length() * 8L * 1_000_000L) / duration)
                                .coerceAtMost(Int.MAX_VALUE.toLong())
                                .toInt()
                        }
                    SourceAudioFormat(
                        mimeType = format.getString(MediaFormat.KEY_MIME),
                        sampleRateHz = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE),
                        channelCount = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                        bitrateBps = containerBitrate ?: estimatedBitrate,
                        durationMs = durationUs?.div(1_000L)
                    )
                }
        } catch (error: Exception) {
            Timber.d(error, "Audio source format inspection failed for ${file.name}")
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun MediaFormat.longOrNull(key: String): Long? =
        if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

    private data class SourceAudioFormat(
        val mimeType: String?,
        val sampleRateHz: Int?,
        val channelCount: Int?,
        val bitrateBps: Int?,
        val durationMs: Long?
    ) {
        fun logSummary(): String =
            "mime=${mimeType ?: "unknown"},sampleRateHz=${sampleRateHz ?: "unknown"}," +
                "channels=${channelCount ?: "unknown"},bitrateBps=${bitrateBps ?: "unknown"}," +
                "durationMs=${durationMs ?: "unknown"}"
    }

    private fun List<AudioDeviceInfo>.typeSummary(): String =
        joinToString(prefix = "[", postfix = "]") { it.type.toString() }

    // BUG-38: minSdk=29, always API O+
    private fun abandonAudioFocus(request: AudioFocusRequest?) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            request?.let { audioManager.abandonAudioFocusRequest(it) }
        } catch (_: Exception) {
        }
    }

    @SuppressLint("InlinedApi")
    companion object {
        private enum class FocusRequestResult { GRANTED, DENIED, SUPERSEDED }

        private val PROJECTED_CAR_OUTPUT_PRIORITY = listOf(
            AudioDeviceInfo.TYPE_BUS,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC
        )

        // TYPE_BLUETOOTH_SCO is intentionally excluded: Android treats it as a
        // telephony route, which can make media voice replies sound narrow-band or robotic.
        private val PERSONAL_OUTPUT_PRIORITY = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE
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
            outputTypes: List<Int>,
            carConnectionType: Int = CarConnection.CONNECTION_TYPE_NOT_CONNECTED
        ): Int? {
            if (isConnectedCarConnectionType(carConnectionType)) return null
            if ((routedTypes + outputTypes).any { isProjectedCarOutputType(it) }) return null
            return firstByPriority(routedTypes, PERSONAL_OUTPUT_PRIORITY)
                ?: firstByPriority(outputTypes, PERSONAL_OUTPUT_PRIORITY)
        }

        internal fun isProjectedCarOutputType(type: Int): Boolean =
            type in PROJECTED_CAR_OUTPUT_PRIORITY

        internal fun isConnectedCarConnectionType(type: Int): Boolean =
            type == CarConnection.CONNECTION_TYPE_NATIVE ||
                type == CarConnection.CONNECTION_TYPE_PROJECTION

        internal fun shouldUseNavigationUsage(
            requestedNavigationUsage: Boolean,
            carConnectionType: Int,
            outputTypes: List<Int> = emptyList()
        ): Boolean = requestedNavigationUsage ||
            isConnectedCarConnectionType(carConnectionType) ||
            outputTypes.any(::isProjectedCarOutputType)

        internal fun chooseEffectivePlaybackSpeed(
            requestedSpeed: Float,
            carConnectionType: Int,
            outputTypes: List<Int> = emptyList()
        ): Float {
            val requested = PlaybackSpeed.coerce(requestedSpeed)
            val carPresent = isConnectedCarConnectionType(carConnectionType) ||
                outputTypes.any(::isProjectedCarOutputType)
            return if (carPresent) 1f else requested
        }

        internal fun shouldTerminateForFocusChange(focusChange: Int): Boolean =
            focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT

        internal fun shouldRetryMediaPlayerError(
            what: Int,
            extra: Int,
            attempt: Int
        ): Boolean = attempt == 0 && (what == INVALID_OPERATION || extra == INVALID_OPERATION)

        private fun firstByPriority(types: List<Int>, priority: List<Int>): Int? =
            priority.firstOrNull { it in types }

        private const val INVALID_OPERATION = -38
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
            it.type in PERSONAL_OUTPUT_PRIORITY
        }
        if (personal.isEmpty()) return null
        val byType = personal.groupBy { it.type }
        for (type in PHONE_FALLBACK_PRIORITY) {
            byType[type]?.firstOrNull()?.let { return it }
        }
        return personal.firstOrNull()
    }
}
