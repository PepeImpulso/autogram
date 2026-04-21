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

    private suspend fun playTone(tone: Int, durationMs: Int) {
        val volume = settingsRepository.get().recordingCuesVolume
        if (volume <= 0) return   // user disabled the cues
        val clampedVolume = volume.coerceIn(1, 100)

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
