package com.autogram.app.infrastructure.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Grabador de audio en formato OGG/OPUS (formato nativo de Telegram).
 * Incluye gestión de errores, límite de duración y limpieza de recursos.
 */
class AudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false
    private var startTimeMs: Long = 0

    /**
     * Inicia la grabación de audio en formato OGG/OPUS.
     * @param outputFile Archivo de salida (debe tener extensión .ogg)
     * @param maxDurationMs Duración máxima en milisegundos (0 = sin límite)
     */
    fun start(outputFile: File, maxDurationMs: Long = 60000): Result<Unit> {
        if (isRecording) {
            return Result.failure(IllegalStateException("Ya hay una grabación en curso"))
        }

        return try {
            this.outputFile = outputFile
            this.startTimeMs = System.currentTimeMillis()

            recorder = createRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setOutputFile(outputFile.absolutePath)

                // Configurar duración máxima si se especifica
                if (maxDurationMs > 0) {
                    setMaxDuration(maxDurationMs.toInt())
                }

                setOnInfoListener { _, what, extra ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        Timber.w("Max recording duration reached; stopping")
                    }
                }

                setOnErrorListener { _, what, extra ->
                    Timber.e("MediaRecorder error: what=$what extra=$extra")
                }

                prepare()
                start()
            }
            isRecording = true
            Timber.d("Recording started: ${outputFile.name}")
            Result.success(Unit)
        } catch (e: IOException) {
            Timber.e(e, "Failed to start recording")
            cleanup()
            Result.failure(e)
        } catch (e: IllegalStateException) {
            Timber.e(e, "MediaRecorder illegal state")
            cleanup()
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "Unexpected recording failure")
            cleanup()
            Result.failure(e)
        }
    }

    /**
     * Detiene la grabación y devuelve el archivo de audio.
     * @return Archivo de audio o null si hubo error.
     */
    fun stop(): File? {
        val duration = System.currentTimeMillis() - startTimeMs
        // MediaRecorder.stop() throws RuntimeException ("stop failed") when
        // called before the encoder has committed any frames — in practice
        // this means the user released the record button too fast (<~1 s on
        // many devices). That's a UX edge case, not a crash worth escalating
        // to Sentry, so we downgrade to a warning here and drop the empty
        // output file. Other failures (hardware error, IO) still surface.
        val current = recorder
        recorder = null
        isRecording = false
        if (current == null) return outputFile
        try {
            current.stop()
            current.release()
            outputFile?.let {
                Timber.d("Recording stopped: ${it.name} (${duration}ms, ${it.length()} bytes)")
            }
            return outputFile
        } catch (e: RuntimeException) {
            // Distinguish the well-known "stop failed" empty-frames case from
            // genuine faults by looking at recording duration and message.
            val isEmptyFrameStop = duration < 1500 ||
                (e.message?.contains("stop failed", ignoreCase = true) == true)
            if (isEmptyFrameStop) {
                Timber.w("Recording too short (${duration}ms); dropping file")
            } else {
                Timber.e(e, "Failed to stop recording (${duration}ms)")
            }
            runCatching { current.release() }
            outputFile?.let { runCatching { if (it.exists()) it.delete() } }
            outputFile = null
            return null
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop recording (${duration}ms)")
            runCatching { current.release() }
            outputFile?.let { runCatching { if (it.exists()) it.delete() } }
            outputFile = null
            return null
        }
    }

    fun isRecording(): Boolean = isRecording

    /**
     * Devuelve la duración de la grabación actual en milisegundos.
     */
    fun currentDurationMs(): Long {
        return if (isRecording) System.currentTimeMillis() - startTimeMs else 0L
    }

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
        } catch (_: Exception) {
        }
        recorder = null
        isRecording = false
    }
}
