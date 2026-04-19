package com.autogram.app.infrastructure.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject

class AudioRecorder @Inject constructor(
    private val context: Context
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false

    fun start(outputFile: File): Result<Unit> {
        return try {
            this.outputFile = outputFile
            recorder = createRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            Timber.d("Recording started: ${outputFile.absolutePath}")
            Result.success(Unit)
        } catch (e: IOException) {
            Timber.e(e, "Failed to start recording")
            cleanup()
            Result.failure(e)
        }
    }

    fun stop(): File? {
        return try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            isRecording = false
            Timber.d("Recording stopped: ${outputFile?.absolutePath}")
            outputFile
        } catch (e: Exception) {
            Timber.e(e, "Error stopping recording")
            cleanup()
            null
        }
    }

    fun isRecording(): Boolean = isRecording

    private fun createRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }

    private fun cleanup() {
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
        isRecording = false
    }
}
