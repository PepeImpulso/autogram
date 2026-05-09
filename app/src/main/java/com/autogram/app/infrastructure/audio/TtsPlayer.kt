package com.autogram.app.infrastructure.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
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
        val requested = locale ?: currentLocaleOrDefault()
        // Android's TextToSpeech silently falls back to the engine default
        // (usually English) when you set a language whose voice data isn't
        // installed — `setLanguage` returns LANG_MISSING_DATA /
        // LANG_NOT_SUPPORTED but we used to throw that value away. Check
        // availability first and swap in a fallback the engine can
        // actually speak, or we read Spanish text with an English voice.
        val effectiveLocale = resolveSupportedLocale(tts, requested)
        // setLanguage() is necessary but not sufficient: many engines
        // (notably Google TTS) keep the previously-selected Voice even when
        // setLanguage returns COUNTRY_AVAILABLE — so a Spanish locale can
        // still come out in an English voice if that's what was loaded
        // last. Picking the actual Voice explicitly is the only reliable
        // way to switch the voice that gets synthesised. We still call
        // setLanguage as a fallback for engines that ignore setVoice or
        // when no concrete Voice can be found.
        runCatching { tts.language = effectiveLocale }
        val pickedVoice = pickInstalledVoice(tts, effectiveLocale)
        if (pickedVoice != null) {
            val accepted = runCatching { tts.voice = pickedVoice }.isSuccess
            Timber.i(
                "TtsPlayer: voice=${pickedVoice.name} locale=${pickedVoice.locale} " +
                    "quality=${pickedVoice.quality} accepted=$accepted (requested=$requested)"
            )
        } else {
            Timber.w(
                "TtsPlayer: no installed Voice found for $effectiveLocale " +
                    "(requested=$requested). Falling back to whatever the " +
                    "engine has loaded — likely English."
            )
        }

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

        // setSpeechRate AFTER stop() so the rate isn't dropped by engines
        // that reset state on stop. The rate set here applies to the next
        // utterance queued via speak(QUEUE_FLUSH) immediately below.
        tts.setSpeechRate(settings.playbackSpeed.coerceIn(0.5f, 2.0f))

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

    /**
     * Stop the engine WITHOUT firing the pending onDone callback. Used by
     * PlayReplyUseCase.pause() when the user wants silence — not "complete
     * this utterance and advance the queue", which is what stop()'s callback
     * triggers via the autoplay-drain speak() callbacks. Symmetric with
     * stop() but drops the pending onDone instead of invoking it.
     */
    fun cancel() {
        pendingOnDone = null
        pendingUtteranceId = null
        runCatching { engine?.stop() }
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

    /**
     * Android's TTS `setLanguage` silently falls back to the engine default
     * (usually English) when the requested voice isn't installed on the
     * device. That's exactly how Spanish text ends up being read by an
     * English voice: `detectLocale` returns `es`, `setLanguage(es)` returns
     * `LANG_MISSING_DATA`, and we never notice.
     *
     * This walks the available fallbacks in order of specificity:
     *   1. the requested locale as-is (`es-ES`, `es-MX`, …)
     *   2. its language-only form (`es`) — covers "generic Spanish installed
     *      but not the country variant"
     *   3. the UI locale, if different from the requested one
     *   4. whatever the engine already has loaded (we leave language unset)
     */
    private fun resolveSupportedLocale(tts: TextToSpeech, requested: Locale): Locale {
        if (isLocaleSupported(tts, requested)) return requested
        val generic = Locale(requested.language)
        if (generic != requested && isLocaleSupported(tts, generic)) {
            Timber.d("TtsPlayer: using generic $generic instead of $requested")
            return generic
        }
        val uiLocale = currentLocaleOrDefault()
        if (uiLocale.language != requested.language && isLocaleSupported(tts, uiLocale)) {
            Timber.w("TtsPlayer: no voice for $requested; falling back to UI locale $uiLocale")
            return uiLocale
        }
        Timber.w(
            "TtsPlayer: no matching voice for $requested (UI=$uiLocale). " +
                "Engine will read it in whatever default voice it has — likely English."
        )
        return uiLocale
    }

    private fun isLocaleSupported(tts: TextToSpeech, locale: Locale): Boolean {
        val code = runCatching { tts.isLanguageAvailable(locale) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        return code == TextToSpeech.LANG_AVAILABLE ||
            code == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
            code == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
    }

    /**
     * Find a concrete `Voice` whose locale matches [locale] AND whose voice
     * data is actually installed on the device. Without this, `tts.voice`
     * silently sticks at whatever the engine loaded last (usually English),
     * even though `setLanguage(es)` returned COUNTRY_AVAILABLE — that's why
     * Spanish replies kept being read in an English voice on real phones.
     *
     * Selection priority:
     *   1. Installed locally + no network needed + matching country variant.
     *   2. Installed locally + no network needed + any country (es-MX over
     *      the wrong language).
     *   3. Anything matching the language at all — even if it would download
     *      from the network. Better than letting the engine fall back to
     *      its English default.
     */
    private fun pickInstalledVoice(tts: TextToSpeech, locale: Locale): Voice? {
        val voices = runCatching { tts.voices }.getOrNull().orEmpty()
        if (voices.isEmpty()) return null
        val targetLang = locale.language.lowercase()
        val targetCountry = locale.country
        val sameLanguage = voices.filter {
            it.locale.language.equals(targetLang, ignoreCase = true)
        }
        val installed = sameLanguage.filter { v ->
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features &&
                !v.isNetworkConnectionRequired
        }
        if (installed.isNotEmpty()) {
            return installed.maxWithOrNull(
                compareBy(
                    { if (targetCountry.isNotEmpty() &&
                            it.locale.country.equals(targetCountry, ignoreCase = true)) 1 else 0 },
                    { it.quality }
                )
            )
        }
        return sameLanguage.firstOrNull()
    }

    companion object {
        /**
         * Cheap content-based locale detection. The priority ladder is:
         *   1. **CJK scripts** — unambiguous, pick immediately.
         *   2. **Spanish-specific characters** (ñ, ¿, ¡, accented vowels) —
         *      strong signal; also immediate.
         *   3. **Stopword scoring (Spanish vs English)** — catches
         *      accent-less Spanish like "voy en camino" that the character
         *      check would miss. A winner is declared only when it leads
         *      by at least [STOPWORD_MARGIN] hits, otherwise we trust the
         *      UI [fallback] — avoids flipping short greetings to the
         *      wrong language.
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
            if (hasHiraganaKatakana) return Locale.JAPANESE
            if (hasHangul) return Locale.KOREAN
            if (hasCjkIdeograph) return Locale.CHINESE
            if (hasSpanishMarker) return Locale("es")

            // Stopword scoring — only reached when the text has no
            // diacritics. "voy en camino" is all Latin-1 ASCII but
            // unmistakably Spanish via the function words it contains.
            val words = text.lowercase()
                .split(WORD_SPLIT_RE)
                .filter { it.isNotEmpty() }
            if (words.isEmpty()) return fallback
            val spanish = words.count { it in SPANISH_STOPWORDS }
            val english = words.count { it in ENGLISH_STOPWORDS }
            return when {
                spanish >= english + STOPWORD_MARGIN -> Locale("es")
                english >= spanish + STOPWORD_MARGIN -> Locale.ENGLISH
                else -> fallback
            }
        }

        private val SPANISH_MARKERS = setOf(
            'ñ', 'Ñ', 'á', 'é', 'í', 'ó', 'ú', 'Á', 'É', 'Í', 'Ó', 'Ú',
            'ü', 'Ü', '¿', '¡'
        )

        // Unicode-letter-aware split. Handles apostrophes inside a word
        // ("I'm" → "i'm") so they count as one token.
        private val WORD_SPLIT_RE = Regex("[^\\p{L}']+")

        // Minimum gap between the two scores before we call a winner.
        // A value of 2 means a short greeting with a single shared word
        // ("hola") doesn't flip a UI-English user to Spanish voice. Lower
        // values false-positive on French / Italian, where SPANISH_STOPWORDS
        // overlaps single function words like "le". The real Spanish-voice
        // bug wasn't detection — it was that setLanguage() didn't switch
        // the engine's actual `Voice`. See pickInstalledVoice.
        private const val STOPWORD_MARGIN: Int = 2

        private val SPANISH_STOPWORDS = setOf(
            // Articles + determiners
            "el", "la", "los", "las", "un", "una", "unos", "unas",
            "este", "esta", "estos", "estas", "ese", "esa", "esos", "esas",
            "esto", "eso", "aquel", "aquella", "aquello",
            // Prepositions + conjunctions
            "de", "del", "en", "a", "al", "y", "o", "u", "con", "sin",
            "por", "para", "como", "pero", "porque", "mas", "si", "ni",
            "entre", "sobre", "hasta", "desde", "ante", "bajo",
            // Pronouns
            "yo", "tu", "el", "ella", "nosotros", "vosotros", "ellos", "ellas",
            "me", "te", "se", "nos", "os", "le", "les", "lo", "mi", "su",
            // Common verbs (indicative)
            "soy", "eres", "es", "somos", "sois", "son",
            "estoy", "estas", "esta", "estamos", "estais", "estan",
            "voy", "vas", "va", "vamos", "vais", "van",
            "he", "has", "ha", "hemos", "habeis", "han",
            "tengo", "tienes", "tiene", "tenemos", "teneis", "tienen",
            "hago", "haces", "hace", "hacemos", "haceis", "hacen",
            "quiero", "quieres", "quiere", "queremos", "quereis", "quieren",
            "puedo", "puedes", "puede", "podemos", "podeis", "pueden",
            "era", "fue", "fui", "fuimos", "fueron", "sera", "fueron",
            // Greetings / courtesy / interjections often accent-less in fast typing
            "hola", "gracias", "buenos", "buenas", "dias", "tardes", "noches",
            "vale", "claro", "bueno", "bien", "mal", "muy", "bastante",
            "ahora", "luego", "despues", "antes", "aqui", "alli", "ahi",
            "hoy", "ayer", "manana", "siempre", "nunca",
            "todo", "todos", "todas", "nada", "algo", "alguien", "nadie",
            "solo", "tambien", "tampoco", "casi", "ya", "aun"
        )

        private val ENGLISH_STOPWORDS = setOf(
            "the", "a", "an", "and", "or", "but", "if", "then", "so",
            "because", "while", "although", "though",
            "is", "are", "was", "were", "be", "been", "being", "am",
            "have", "has", "had", "do", "does", "did",
            "can", "could", "will", "would", "should", "may", "might",
            "in", "on", "at", "to", "from", "of", "for", "with", "about",
            "as", "by", "into", "onto", "over", "under", "between",
            "i", "you", "he", "she", "it", "we", "they", "me", "him", "her",
            "us", "them", "my", "your", "his", "her", "its", "our", "their",
            "this", "that", "these", "those",
            "not", "no", "yes", "nope", "yeah",
            "hello", "hi", "hey", "thanks", "thank", "okay", "ok", "please",
            "good", "bad", "great", "fine",
            "what", "when", "where", "why", "how", "who", "which",
            "here", "there", "now", "later", "before", "after", "today",
            "tomorrow", "yesterday",
            "going", "coming", "home", "work", "going", "time"
        )
    }
}
