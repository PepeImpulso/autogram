package com.autogram.app.infrastructure.audio

import android.media.AudioManager
import android.media.ToneGenerator
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper over [ToneGenerator] to play the short start/end cues that bookend a voice
 * recording — audio feedback for the driver that the mic actually opened / closed without
 * needing them to look at the screen.
 *
 * Uses two different tones so the two events are distinguishable by ear even in noisy
 * environments. Low volume (50/100) by design: this is a hint, not a notification.
 */
@Singleton
class RecordingSounds @Inject constructor() {

    fun playStart() = playTone(ToneGenerator.TONE_PROP_BEEP, durationMs = 120)

    // A two-tone end cue contrasts with the single-beep start.
    fun playEnd() = playTone(ToneGenerator.TONE_PROP_BEEP2, durationMs = 150)

    private fun playTone(tone: Int, durationMs: Int) {
        // ToneGenerator must be released after each use; keeping one alive as a field
        // would leak AudioTrack handles across screens / recordings.
        var gen: ToneGenerator? = null
        try {
            gen = ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME)
            gen.startTone(tone, durationMs)
        } catch (e: Exception) {
            Timber.w(e, "RecordingSounds.playTone failed (tone=$tone)")
        } finally {
            // Release on the ToneGenerator thread after the sound has had time to play.
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

    companion object {
        // 50/100 = half max, audible over wind/road noise without being jarring.
        private const val VOLUME = 50
    }
}
