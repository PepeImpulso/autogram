package com.autogram.app.infrastructure.audio

import android.media.AudioManager
import android.media.ToneGenerator
import com.autogram.app.domain.repository.SettingsRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper over [ToneGenerator] to play the short start/end cues that bookend a voice
 * recording — audio feedback for the driver that the mic actually opened / closed without
 * needing them to look at the screen.
 *
 * Uses two different tones so the two events are distinguishable by ear even in noisy
 * environments. Volume and on/off come from user settings (AppSettings.recordingCuesVolume,
 * 0..100 where 0 disables the cues).
 */
@Singleton
class RecordingSounds @Inject constructor(
    private val settingsRepository: SettingsRepository
) {

    suspend fun playStart() = playTone(ToneGenerator.TONE_PROP_BEEP, durationMs = 120)

    // A two-tone end cue contrasts with the single-beep start.
    suspend fun playEnd() = playTone(ToneGenerator.TONE_PROP_BEEP2, durationMs = 150)

    // Short ACK chime after a shortcut reaches Telegram — gives the driver
    // audible confirmation ("yes, that actually went out") without needing
    // to look at the status pill. Uses PROP_ACK which is a pleasant
    // two-note rising chirp on most devices.
    suspend fun playSent() = playTone(ToneGenerator.TONE_PROP_ACK, durationMs = 200)

    // Distinct error tone — TONE_PROP_NACK is a descending/harsh buzzer
    // across OEM implementations, so it reads as "something went wrong"
    // even without context. Longer than the ACK so a driver hears it
    // over road noise.
    suspend fun playError() = playTone(ToneGenerator.TONE_PROP_NACK, durationMs = 350)

    private suspend fun playTone(tone: Int, durationMs: Int) {
        val volume = settingsRepository.get().recordingCuesVolume
        if (volume <= 0) return   // user disabled the cues
        // v1.20.14: user feedback was that the cue was inaudible over road
        // noise even at the default 50%. ToneGenerator's volume range is
        // 1-100 with 100 being the system MEDIA-stream cap, so we apply a
        // 2× boost: the slider's 0-50 range now covers 0-100 effective
        // volume, and 51-100 stays clamped at 100 (no perceptible change
        // beyond 50). Doubles loudness in the most-used range without
        // breaking the slider semantics.
        val clampedVolume = (volume * 2).coerceIn(1, 100)

        // ToneGenerator must be released after each use; keeping one alive as a field
        // would leak AudioTrack handles across screens / recordings.
        var gen: ToneGenerator? = null
        try {
            gen = ToneGenerator(AudioManager.STREAM_MUSIC, clampedVolume)
            gen.startTone(tone, durationMs)
        } catch (e: Exception) {
            Timber.w(e, "RecordingSounds.playTone failed (tone=$tone)")
        } finally {
            // Release on a daemon thread after the sound has had time to play.
            // Releasing immediately while the tone is queued cuts it off on some devices.
            val toRelease = gen
            if (toRelease != null) {
                Thread {
                    Thread.sleep((durationMs + 50).toLong())
                    runCatching { toRelease.release() }
                }.apply { isDaemon = true }.start()
            }
        }
    }
}
