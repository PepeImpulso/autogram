package com.autogram.app.infrastructure.audio

/**
 * Single source of truth for the user-facing playback speed setting.
 *
 * Both MediaPlayer voice playback and TextToSpeech playback consume this
 * range, so the Settings slider means the same thing for text and voice.
 */
object PlaybackSpeed {
    const val MIN: Float = 0.5f
    const val MAX: Float = 2.0f
    const val DEFAULT: Float = 1.25f

    fun coerce(value: Float): Float = value.coerceIn(MIN, MAX)
}
