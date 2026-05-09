package com.autogram.app.infrastructure.audio

import android.media.MediaMetadataRetriever
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject

/**
 * Filtro de mensajes vacíos/silenciosos.
 * Valida que las grabaciones tengan duración mínima y contenido de audio real.
 */
class EmptyMessageFilter @Inject constructor() {

    data class FilterResult(
        val accepted: Boolean,
        val reason: String? = null
    )

    companion object {
        /** Umbral RMS para considerar silencio (valor empírico para OGG/OPUS) */
        private const val SILENCE_THRESHOLD = 500
        /** Tamaño mínimo de archivo en bytes para considerarse válido */
        private const val MIN_FILE_SIZE_BYTES = 2000L
    }

    /**
     * Valida un archivo de audio contra los criterios de filtrado.
     * @param audioFile Archivo de audio OGG/OPUS
     * @param minDurationMs Duración mínima en milisegundos (por defecto 1500ms)
     */
    fun validate(audioFile: File, minDurationMs: Long = 1500): FilterResult {
        // 1. Verificar que el archivo existe
        if (!audioFile.exists()) {
            return FilterResult(accepted = false, reason = "El archivo no existe")
        }

        // 2. Verificar tamaño mínimo del archivo
        if (audioFile.length() < MIN_FILE_SIZE_BYTES) {
            return FilterResult(
                accepted = false,
                reason = "Archivo demasiado pequeño (${audioFile.length()} bytes)"
            )
        }

        // 3. Verificar duración mínima usando MediaMetadataRetriever
        val durationMs = getDurationMs(audioFile)
        if (durationMs != null && durationMs < minDurationMs) {
            return FilterResult(
                accepted = false,
                reason = "Duración insuficiente (${durationMs}ms < ${minDurationMs}ms)"
            )
        }

        // 4. Verificar que no es silencio puro (análisis RMS simplificado)
        if (isSilent(audioFile)) {
            return FilterResult(accepted = false, reason = "Grabación silenciosa (sin voz detectada)")
        }

        return FilterResult(accepted = true)
    }

    /**
     * Obtiene la duración del archivo de audio en milisegundos.
     */
    fun getDurationMs(audioFile: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(audioFile.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationStr?.toLongOrNull()
        } catch (e: Exception) {
            Timber.w(e, "Could not read audio duration")
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Detecta si el audio es esencialmente silencio.
     * Usa un análisis simplificado del contenido del archivo OGG.
     * NOTA: Para un análisis preciso se necesitaría decodificar el OPUS,
     * pero para esta implementación usamos una heurística basada en el
     * ratio tamaño/duración.
     */
    private fun isSilent(audioFile: File): Boolean {
        val durationMs = getDurationMs(audioFile) ?: return false
        if (durationMs <= 0) return true

        // Ratio bytes/segundo. OGG/OPUS silencioso tiene muy poca información.
        // Un archivo OPUS de voz normal a ~32kbps ≈ 4000 bytes/segundo
        // Un archivo silencioso suele ser < 500 bytes/segundo
        val bytesPerSecond = (audioFile.length() * 1000.0) / durationMs
        Timber.d("Audio analysis: ${audioFile.length()} bytes, ${durationMs}ms, ${bytesPerSecond.toInt()} bytes/s")

        return bytesPerSecond < SILENCE_THRESHOLD
    }
}
