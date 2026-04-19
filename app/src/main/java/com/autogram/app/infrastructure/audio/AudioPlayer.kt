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

class AudioPlayer @Inject constructor(
    private val context: Context
) {
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false

    fun play(file: File, onComplete: () -> Unit = {}): Result<Unit> {
        return try {
            stop()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .build()
                )
                setOnCompletionListener {
                    isPlaying = false
                    onComplete()
                }
                setOnErrorListener { _, what, extra ->
                    Timber.e("MediaPlayer error: what=$what extra=$extra")
                    isPlaying = false
                    onComplete()
                    true
                }
                prepare()
                start()
            }
            isPlaying = true
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to play audio")
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
        mediaPlayer?.let {
            it.start()
            isPlaying = true
        }
    }

    fun stop() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
        isPlaying = false
    }

    fun isPlaying(): Boolean = isPlaying
}
