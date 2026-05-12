package com.autogram.app.infrastructure.audio

import com.autogram.app.domain.model.AudioFocusMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.24.1 — single source of truth for "what happened the last time
 * Autogram tried to play audio". Both [AudioPlayer] (voice files) and
 * [TtsPlayer] (TTS text / summaries) emit into this on every play.
 *
 * Exposed on the phone Settings → AA audio routing diagnostics row so
 * the user can see in-car whether their tap produced any playback at
 * all, which path it took (file vs. TTS), whether audio focus was
 * granted, and which output device the OS routed it to. Without this
 * level of detail v1.24.0's "Last routed device" row stayed empty when
 * the failing path was TTS — which is exactly what happened on Pepe's
 * Seat Ateca because TtsPlayer never requested focus.
 */
@Singleton
class PlaybackDiagnostics @Inject constructor() {

    enum class Source { AUDIO_PLAYER, TTS_PLAYER }

    data class PlaybackAttempt(
        val source: Source,
        val focusGranted: Boolean,
        val routedDeviceType: Int?,
        val focusMode: AudioFocusMode,
        val timestamp: Long
    )

    private val _lastAttempt = MutableStateFlow<PlaybackAttempt?>(null)
    val lastAttempt: StateFlow<PlaybackAttempt?> = _lastAttempt.asStateFlow()

    fun record(
        source: Source,
        focusGranted: Boolean,
        routedDeviceType: Int?,
        focusMode: AudioFocusMode
    ) {
        _lastAttempt.value = PlaybackAttempt(
            source = source,
            focusGranted = focusGranted,
            routedDeviceType = routedDeviceType,
            focusMode = focusMode,
            timestamp = System.currentTimeMillis()
        )
    }
}
