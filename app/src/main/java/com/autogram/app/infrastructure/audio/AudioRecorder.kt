package com.autogram.app.infrastructure.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Grabador de audio en formato OGG/OPUS (formato nativo de Telegram).
 * Incluye gestión de errores, límite de duración y limpieza de recursos.
 */
class AudioRecorder @Inject constructor(
    private val context: Context
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
                        Timber.w("Duración máxima alcanzada, deteniendo grabación")
                    }
                }

                setOnErrorListener { _, what, extra ->
                    Timber.e("MediaRecorder error: what=$what extra=$extra")
                }

                prepare()
                start()
            }
            isRecording = true
            Timber.d("Grabación iniciada: ${outputFile.absolutePath}")
            Result.success(Unit)
        } catch (e: IOException) {
            Timber.e(e, "Error al iniciar grabación")
            cleanup()
            Result.failure(e)
        } catch (e: IllegalStateException) {
            Timber.e(e, "Estado ilegal del MediaRecorder")
            cleanup()
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "Error inesperado al grabar")
            cleanup()
            Result.failure(e)
        }
    }

    /**
     * Detiene la grabación y devuelve el archivo de audio.
     * @return Archivo de audio o null si hubo error.
     */
    fun stop(): File? {
        return try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            isRecording = false

            val duration = System.currentTimeMillis() - startTimeMs
            outputFile?.let {
                Timber.d("Grabación detenida: ${it.absolutePath} (${duration}ms, ${it.length()} bytes)")
            }
            outputFile
        } catch (e: Exception) {
            Timber.e(e, "Error al detener grabación")
            cleanup()
            null
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
