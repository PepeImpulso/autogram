package com.autogram.app.infrastructure.audio

import java.io.File
import javax.inject.Inject

class EmptyMessageFilter @Inject constructor() {

    data class FilterResult(
        val accepted: Boolean,
        val reason: String? = null
    )

    fun validate(audioFile: File, minDurationMs: Long = 1500): FilterResult {
        // Check file exists
        if (!audioFile.exists()) {
            return FilterResult(accepted = false, reason = "File does not exist")
        }

        // Check minimum file size (very small = likely empty)
        if (audioFile.length() < 1000) { // Less than 1KB
            return FilterResult(accepted = false, reason = "File too small (${audioFile.length()} bytes)")
        }

        // TODO: Implement actual duration check using MediaMetadataRetriever
        // TODO: Implement RMS amplitude analysis for silence detection

        return FilterResult(accepted = true)
    }
}
