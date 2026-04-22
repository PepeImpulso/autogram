package com.autogram.app.infrastructure.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.appcompat.app.AppCompatDelegate
import com.autogram.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Thin wrapper over Android's TextToSpeech engine.
 *
 *  - Uses the UI locale from [AppCompatDelegate.getApplicationLocales], so a
 *    Merlin reply in English plays back in an English voice even if the
 *    device default is Spanish.
 *  - Respects [AppSettings.playbackSpeed] via setSpeechRate.
 *  - Initialisation is lazy + cached. engine.init is callback-based so we
 *    wrap it as a suspend function.
 */
@Singleton
class TtsPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var initialized = false

    // Tracks the callback for the utterance currently in flight. Android
    // TTS does NOT invoke onDone when the engine is `stop()`ed — it
    // silently drops the utterance. Without this bookkeeping, calling
    // stop() between successive speak() calls (which we do to flush the
    // queue) would lose the onDone callback forever. The pending queue
    // in PlayReplyUseCase relies on that callback to fire playNext,
    // so a lost callback = queue stuck forever.
    @Volatile private var pendingOnDone: (() -> Unit)? = null
    // Utterance id the pending callback is tied to. TTS engine's
    // OnUtteranceProgressListener delivers onDone/onError for each
    // utterance on its own binder thread, which can race a subsequent
    // speak() that already replaced pendingOnDone — we'd fire the new
    // utterance's callback on the old utterance's listener event.
    // Matching `id` against the one we handed to `tts.speak()` shuts
    // that race cleanly.
    @Volatile private var pendingUtteranceId: String? = null

    /**
     * Reads [text] aloud. By default picks the voice for the UI locale, but
     * callers that know the text's actual language (e.g. a summary generated
     * in the original message's language, which may differ from the UI) can
     * pass an explicit [locale] so a Spanish summary isn't read by an English
     * voice.
     */
    suspend fun speak(text: String, locale: Locale? = null, onDone: () -> Unit = {}) {
        if (text.isBlank()) {
            onDone()
            return
        }
        val tts = ensureEngine() ?: run {
            Timber.w("TtsPlayer: engine unavailable")
            onDone()
            return
        }
        val settings = settingsRepository.get()
        val effectiveLocale = locale ?: currentLocaleOrDefault()
        runCatching { tts.language = effectiveLocale }

        tts.setSpeechRate(settings.playbackSpeed.coerceIn(0.5f, 2.0f))
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        )

        // Hard-stop anything currently queued/playing before we hand the
        // new utterance to the engine. Combined with QUEUE_FLUSH below
        // this means only one utterance is ever in flight — prevents
        // the "two voices overlapping" bug where a late-arriving reply
        // started speaking before the previous one finished.
        //
        // Android's TTS swallows onDone on explicit stop(), so the old
        // pending callback has to be fired manually here — otherwise
        // PlayReplyUseCase.playNext never runs and the queue freezes.
        val previousOnDone = pendingOnDone
        pendingOnDone = null
        pendingUtteranceId = null
        runCatching { tts.stop() }
        previousOnDone?.invoke()

        val utteranceId = UUID.randomUUID().toString()
        pendingOnDone = onDone
        pendingUtteranceId = utteranceId
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = fireIfMatches(id)
            @Deprecated("Deprecated in API 21", ReplaceWith(""))
            override fun onError(id: String?) = fireIfMatches(id)
            override fun onError(id: String?, errorCode: Int) = fireIfMatches(id)

            private fun fireIfMatches(id: String?) {
                // Listener events for a stopped utterance may arrive
                // AFTER the next speak() has replaced pendingOnDone.
                // Only fire if the event belongs to the utterance we
                // currently track — otherwise the new utterance's
                // callback would be invoked at the old utterance's
                // stop event, chaining playNext() too early.
                if (id == null || id != pendingUtteranceId) return
                val cb = pendingOnDone
                pendingOnDone = null
                pendingUtteranceId = null
                cb?.invoke()
            }
        })
        runCatching {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }.onFailure {
            Timber.w(it, "TtsPlayer.speak failed")
            val cb = pendingOnDone
            pendingOnDone = null
            pendingUtteranceId = null
            cb?.invoke()
        }
    }

    fun stop() {
        // Mirror the callback-reclaim dance from speak() so external
        // callers (PlayReplyUseCase.stopAll, recording start) don't
        // strand a pending onDone. If the utterance had already
        // finished organically, pendingOnDone is already null and this
        // is a no-op.
        val previousOnDone = pendingOnDone
        pendingOnDone = null
        pendingUtteranceId = null
        runCatching { engine?.stop() }
        previousOnDone?.invoke()
    }

    /** True when the engine currently has an utterance in flight. */
    fun isSpeaking(): Boolean = runCatching { engine?.isSpeaking == true }.getOrDefault(false)

    fun shutdown() {
        runCatching { engine?.stop(); engine?.shutdown() }
        engine = null
        initialized = false
    }

    private suspend fun ensureEngine(): TextToSpeech? {
        if (initialized) return engine
        return suspendCoroutine { cont ->
            val tts = TextToSpeech(context) { status ->
                initialized = status == TextToSpeech.SUCCESS
                cont.resume(if (initialized) engine else null)
            }
            engine = tts
        }
    }

    private fun currentLocaleOrDefault(): Locale {
        val list = AppCompatDelegate.getApplicationLocales()
        if (!list.isEmpty) return list[0] ?: Locale.getDefault()
        return Locale.getDefault()
    }

    companion object {
        /**
         * Cheap content-based locale detection. Used when we need to read
         * text whose language might not match the UI (e.g. a summary produced
         * in the original message's language). Checks unicode scripts in
         * descending specificity: CJK scripts first (they're unambiguous),
         * then Spanish-specific markers, else falls back to [fallback].
         */
        fun detectLocale(text: String, fallback: Locale): Locale {
            if (text.isBlank()) return fallback
            var hasHiraganaKatakana = false
            var hasHangul = false
            var hasCjkIdeograph = false
            var hasSpanishMarker = false
            for (ch in text) {
                val block = Character.UnicodeBlock.of(ch)
                when (block) {
                    Character.UnicodeBlock.HIRAGANA,
                    Character.UnicodeBlock.KATAKANA -> hasHiraganaKatakana = true
                    Character.UnicodeBlock.HANGUL_SYLLABLES,
                    Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO,
                    Character.UnicodeBlock.HANGUL_JAMO -> hasHangul = true
                    Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
                    Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
                    Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS -> hasCjkIdeograph = true
                    else -> Unit
                }
                if (ch in SPANISH_MARKERS) hasSpanishMarker = true
            }
            return when {
                hasHiraganaKatakana -> Locale.JAPANESE
                hasHangul -> Locale.KOREAN
                hasCjkIdeograph -> Locale.CHINESE
                hasSpanishMarker -> Locale("es")
                else -> fallback
            }
        }

        private val SPANISH_MARKERS = setOf(
            'ñ', 'Ñ', 'á', 'é', 'í', 'ó', 'ú', 'Á', 'É', 'Í', 'Ó', 'Ú',
            'ü', 'Ü', '¿', '¡'
        )
    }
}
