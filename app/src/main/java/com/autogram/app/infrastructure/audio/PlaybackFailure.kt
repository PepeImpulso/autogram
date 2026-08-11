package com.autogram.app.infrastructure.audio

/** Playback could not start because Android denied the requested audio focus. */
class AudioFocusDeniedException(source: String) :
    IllegalStateException("Audio focus denied for $source")

/** Playback was interrupted after Android Auto or another audio owner took focus. */
class AudioFocusLostException(source: String, focusChange: Int) :
    IllegalStateException("Audio focus lost for $source (change=$focusChange)")

/** Android's TTS engine rejected or failed an utterance. */
class TtsSynthesisException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
