package com.autogram.app.infrastructure.transcription

import java.io.File
import java.util.Locale

/**
 * Pluggable abstraction over "turn an audio file into text". Current
 * implementation wraps Android's on-device `SpeechRecognizer`, which ships
 * with Google apps on every Android phone and works offline once the user
 * has downloaded the language pack (first-run one-time prompt).
 *
 * A future impl (e.g. whisper.cpp) can replace this under the same interface
 * without touching the use case layer.
 */
interface TranscriptionEngine {

    sealed class Result {
        data class Success(val text: String) : Result()
        object NotInstalled : Result()   // engine itself not available (no Google services / no on-device recognizer)
        object LanguageUnavailable : Result()  // engine up but no pack for the requested locale
        data class Error(val message: String) : Result()
    }

    /**
     * Transcribe [audioFile] (an OGG/Opus voice note from Telegram) into text
     * using the best voice model for [locale]. Returns a Result sealed type
     * so the caller can distinguish "no engine installed" from transient
     * errors.
     *
     * Suspend — the implementation is expected to decode the audio and run
     * the recognition asynchronously.
     */
    suspend fun transcribe(audioFile: File, locale: Locale): Result
}
