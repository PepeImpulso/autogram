package com.autogram.app.infrastructure.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.appcompat.app.AppCompatDelegate
import com.autogram.app.domain.model.AudioFocusMode
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.infrastructure.platform.CarConnectionMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

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
    private val settingsRepository: SettingsRepository,
    private val diagnostics: PlaybackDiagnostics,
    private val carConnectionMonitor: CarConnectionMonitor
) {
    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var initialized = false

    /** Serializes engine construction so concurrent first utterances share one instance. */
    private val engineInitMutex = Mutex()
    private val speechStartMutex = Mutex()
    private val engineStateLock = Any()
    private val speechStateLock = Any()
    private val engineCommandLock = Any()
    private val focusStateLock = Any()
    private var engineGeneration: Long = 0L
    private var speechControlGeneration: Long = 0L
    private val pendingStartsByGeneration = mutableMapOf<Long, Int>()

    // v1.24.1 — TTS audio focus. Mirrors AudioPlayer.requestAudioFocus
    // shape. Per-utterance acquire/release so a long idle gap between
    // queued messages doesn't strand focus held by us. Without this,
    // AA's audio policy treats USAGE_MEDIA TTS from a background app as
    // unauthenticated and silently drops it — root cause of the
    // "real incoming messages never play" symptom on some head units (e.g. SEAT).
    private data class FocusHandle(
        val token: String,
        val request: AudioFocusRequest
    )

    private data class ActiveSpeech(
        val token: String,
        val remainingUtteranceIds: MutableSet<String>,
        val onDone: () -> Unit,
        val onError: ((Throwable) -> Unit)?
    )

    private data class TtsRequestFailure(
        val description: String,
        val cause: Throwable? = null
    )

    @Volatile private var audioFocusHandle: FocusHandle? = null
    @Volatile private var activeSpeech: ActiveSpeech? = null

    /**
     * Reads [text] aloud. By default picks the voice for the UI locale, but
     * callers that know the text's actual language (e.g. a summary generated
     * in the original message's language, which may differ from the UI) can
     * pass an explicit [locale] so a Spanish summary isn't read by an English
     * voice.
     */
    suspend fun speak(
        text: String,
        locale: Locale? = null,
        onDone: () -> Unit = {},
        speed: Float? = null,
        onError: ((Throwable) -> Unit)? = null
    ) {
        if (text.isBlank()) {
            onDone()
            return
        }
        val startGeneration = beginPendingStart()
        try {
            speechStartMutex.withLock {
                if (!isStartGenerationCurrent(startGeneration)) return@withLock
                val token = UUID.randomUUID().toString()
                val maxInputLength = runCatching { TextToSpeech.getMaxSpeechInputLength() }
                    .getOrDefault(DEFAULT_MAX_SPEECH_INPUT_LENGTH)
                    .coerceAtLeast(1)
                val chunks = chunkText(text, maxInputLength)
                var attempt = 0
                var utteranceIds = utteranceIds(token, attempt, chunks.size)

                // A direct/manual caller may replace an active utterance. End the
                // previous session exactly once before installing the replacement.
                failActiveSpeech(
                    TtsSynthesisException("TTS utterance replaced by a newer request"),
                    stopEngine = true
                )
                val installed = synchronized(speechStateLock) {
                    if (startGeneration != speechControlGeneration) {
                        false
                    } else {
                        activeSpeech = ActiveSpeech(
                            token = token,
                            remainingUtteranceIds = utteranceIds.toMutableSet(),
                            onDone = onDone,
                            onError = onError
                        )
                        true
                    }
                }
                if (!installed) return@withLock

                var tts = ensureEngine() ?: run {
                    failSpeech(
                        token,
                        TtsSynthesisException("TTS engine unavailable"),
                        stopEngine = false
                    )
                    return@withLock
                }
                if (!isSpeechActive(token)) return@withLock

                val settings = settingsRepository.get()
                if (!isSpeechActive(token)) return@withLock
                val requested = locale ?: currentLocaleOrDefault()
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val outputTypes = runCatching {
                    audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
                }.getOrDefault(emptyList())
                val useNavigationUsage = AudioPlayer.shouldUseNavigationUsage(
                    requestedNavigationUsage = settings.ttsAsNavigationPrompt,
                    carConnectionType = carConnectionMonitor.currentType(),
                    outputTypes = outputTypes
                )
                val ttsAttrs = if (useNavigationUsage) {
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                } else {
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                }
                val focusGranted = requestAudioFocus(
                    token = token,
                    attributes = ttsAttrs,
                    focusMode = settings.audioFocusMode
                )
                diagnostics.record(
                    source = PlaybackDiagnostics.Source.TTS_PLAYER,
                    focusGranted = focusGranted,
                    routedDeviceType = currentTtsRouteType(ttsAttrs),
                    focusMode = settings.audioFocusMode
                )
                if (!focusGranted) {
                    Timber.w("TTS audio focus denied; synthesis was not started")
                    failSpeech(
                        token,
                        AudioFocusDeniedException("TTS"),
                        stopEngine = false
                    )
                    return@withLock
                }
                if (!isSpeechActive(token)) {
                    abandonAudioFocus(token)
                    return@withLock
                }

                val speechRate = PlaybackSpeed.coerce(speed ?: settings.playbackSpeed)
                val progressListener = object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit

                    override fun onDone(id: String?) {
                        if (id != null) completeChunk(token, id)
                    }

                    @Deprecated("Deprecated in API 21", ReplaceWith(""))
                    override fun onError(id: String?) {
                        if (id != null && isUtteranceActive(token, id)) {
                            failSpeech(
                                token,
                                TtsSynthesisException("TTS engine failed utterance $id"),
                                stopEngine = true
                            )
                        }
                    }

                    override fun onError(id: String?, errorCode: Int) {
                        if (id != null && isUtteranceActive(token, id)) {
                            failSpeech(
                                token,
                                TtsSynthesisException(
                                    "TTS engine failed utterance $id (error=$errorCode)"
                                ),
                                stopEngine = true
                            )
                        }
                    }
                }
                while (isSpeechActive(token)) {
                    val configurationFailure = configureEngineForSpeech(
                        tts = tts,
                        requestedLocale = requested,
                        attributes = ttsAttrs,
                        speechRate = speechRate,
                        progressListener = progressListener
                    )
                    val queueFailure = configurationFailure ?: queueChunks(
                        tts = tts,
                        token = token,
                        chunks = chunks,
                        utteranceIds = utteranceIds
                    )
                    if (queueFailure == null) return@withLock

                    if (shouldRetryRejectedRequest(attempt) && isSpeechActive(token)) {
                        attempt += 1
                        utteranceIds = utteranceIds(token, attempt, chunks.size)
                        if (!replaceActiveUtteranceIds(token, utteranceIds)) return@withLock
                        Timber.w(
                            queueFailure.cause,
                            "TtsPlayer: engine rejected request; rebuilding and retrying once " +
                                "(${queueFailure.description})"
                        )
                        tts = recreateEngine(tts) ?: run {
                            failSpeech(
                                token,
                                TtsSynthesisException(
                                    "TTS engine unavailable after rejected request",
                                    queueFailure.cause
                                ),
                                stopEngine = false
                            )
                            return@withLock
                        }
                        continue
                    }

                    val error = TtsSynthesisException(
                        "TTS engine rejected request after retry (${queueFailure.description})",
                        queueFailure.cause
                    )
                    failSpeech(token, error, stopEngine = false)
                    discardEngine(tts)
                    return@withLock
                }
            }
        } finally {
            endPendingStart(startGeneration)
        }
    }

    fun stop() {
        val speech = invalidateSpeechRequests()
        stopEngineSafely()
        abandonAudioFocus(speech?.token)
        speech?.let(::deliverSuccess)
    }

    /**
     * Stop the engine WITHOUT firing the pending onDone callback. Used by
     * PlayReplyUseCase.pause() when the user wants silence — not "complete
     * this utterance and advance the queue", which is what stop()'s callback
     * triggers via the autoplay-drain speak() callbacks. Symmetric with
     * stop() but drops the pending onDone instead of invoking it.
     */
    fun cancel() {
        val speech = invalidateSpeechRequests()
        stopEngineSafely()
        abandonAudioFocus(speech?.token)
    }

    /**
     * True from the moment a request enters setup until its sole terminal
     * callback. This closes TextToSpeech.isSpeaking's asynchronous start gap.
     */
    fun isSpeaking(): Boolean {
        val tracked = synchronized(speechStateLock) {
            activeSpeech != null ||
                pendingStartsByGeneration.getOrDefault(speechControlGeneration, 0) > 0
        }
        return tracked || runCatching { engine?.isSpeaking == true }.getOrDefault(false)
    }

    fun shutdown() {
        val speech = invalidateSpeechRequests()
        val engineToShutdown = synchronized(engineStateLock) {
            engineGeneration += 1
            initialized = false
            engine.also { engine = null }
        }
        runCatching {
            synchronized(engineCommandLock) {
                engineToShutdown?.stop()
                engineToShutdown?.shutdown()
            }
        }
        abandonAudioFocus()
        speech?.let {
            deliverError(it, TtsSynthesisException("TTS engine shut down during speech"))
        }
    }

    /**
     * v1.24.1 — symmetric with AudioPlayer.requestAudioFocus(). Mode is
     * read from [AppSettings.audioFocusMode] (cycled by the user from
     * Settings → AA audio routing). Permanent and transient focus loss abort
     * the utterance through its error callback; CAN_DUCK keeps speaking.
     */
    private fun requestAudioFocus(
        token: String,
        attributes: AudioAttributes,
        focusMode: AudioFocusMode
    ): Boolean {
        // Release any focus we still hold from a prior utterance before
        // re-requesting. AudioFocusRequest leaks across calls otherwise.
        abandonAudioFocus()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focusGainType = when (focusMode) {
            AudioFocusMode.TRANSIENT_MAY_DUCK -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioFocusMode.TRANSIENT_EXCLUSIVE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioFocusMode.GAIN -> AudioManager.AUDIOFOCUS_GAIN
        }
        val request = try {
            AudioFocusRequest.Builder(focusGainType)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> failSpeech(
                            token,
                            AudioFocusLostException("TTS", focusChange),
                            stopEngine = true
                        )
                        // CAN_DUCK is intentionally allowed to continue. The
                        // navigation path remains audible over the other owner.
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                        AudioManager.AUDIOFOCUS_GAIN -> Unit
                    }
                }
                .build()
        } catch (e: Exception) {
            Timber.w(e, "TTS AudioFocusRequest build failed; treating as denied")
            return false
        }
        synchronized(focusStateLock) {
            audioFocusHandle = FocusHandle(token, request)
        }
        val granted = runCatching {
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.onFailure {
            Timber.w(it, "TTS audio focus request failed; treating as denied")
        }.getOrDefault(false)
        if (!granted) {
            abandonAudioFocus(token)
            return false
        }
        val stillOwned = synchronized(focusStateLock) {
            val current = audioFocusHandle
            current?.token == token && current.request === request
        }
        if (!stillOwned) {
            // Cancellation can abandon the handle between installing it and
            // the platform returning GRANTED. Release the just-granted request
            // directly because it is no longer reachable through our state.
            runCatching { audioManager.abandonAudioFocusRequest(request) }
            return false
        }
        return true
    }

    private fun abandonAudioFocus(token: String? = null) {
        val handle = synchronized(focusStateLock) {
            val current = audioFocusHandle ?: return
            if (token != null && current.token != token) return
            audioFocusHandle = null
            current
        }
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.abandonAudioFocusRequest(handle.request)
        } catch (_: Exception) {
        }
    }

    /**
     * v1.24.1 — best-effort lookup of which output device the OS would
     * route TTS to right now, given [attributes]. Used to feed
     * [PlaybackDiagnostics] so the AA Settings tab can show "TTS · BUS"
     * (= car speaker) vs "TTS · BUILTIN_SPEAKER" (= phone speaker, audio
     * never reached the car) right after every TTS utterance starts.
     * Returns null on API < 33 where [AudioManager.getAudioDevicesForAttributes]
     * isn't available.
     */
    private fun currentTtsRouteType(attributes: AudioAttributes): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.getAudioDevicesForAttributes(attributes).firstOrNull()?.type
        }.getOrNull()
    }

    private fun configureEngineForSpeech(
        tts: TextToSpeech,
        requestedLocale: Locale,
        attributes: AudioAttributes,
        speechRate: Float,
        progressListener: UtteranceProgressListener
    ): TtsRequestFailure? {
        val effectiveLocale = resolveSupportedLocale(tts, requestedLocale)
        val languageResult = runCatching { tts.setLanguage(effectiveLocale) }
        if (languageResult.isFailure || languageResult.getOrNull().let { it == null || it < 0 }) {
            return TtsRequestFailure(
                "language setup result=${languageResult.getOrNull()}",
                languageResult.exceptionOrNull()
            )
        }

        val pickedVoice = pickInstalledVoice(tts, effectiveLocale)
        if (pickedVoice != null) {
            val voiceResult = runCatching { tts.setVoice(pickedVoice) }
            val accepted = voiceResult.getOrNull() == TextToSpeech.SUCCESS
            Timber.i(
                "TtsPlayer: voice=${pickedVoice.name} locale=${pickedVoice.locale} " +
                    "quality=${pickedVoice.quality} accepted=$accepted " +
                    "(requested=$requestedLocale)"
            )
        } else {
            Timber.w(
                "TtsPlayer: no installed Voice found for $effectiveLocale " +
                    "(requested=$requestedLocale). Falling back to the loaded engine voice."
            )
        }

        val attributesResult = runCatching { tts.setAudioAttributes(attributes) }
        if (attributesResult.isFailure || attributesResult.getOrNull() != TextToSpeech.SUCCESS) {
            return TtsRequestFailure(
                "audio attributes result=${attributesResult.getOrNull()}",
                attributesResult.exceptionOrNull()
            )
        }

        val rateResult = runCatching { tts.setSpeechRate(speechRate) }
        if (rateResult.isFailure || rateResult.getOrNull() != TextToSpeech.SUCCESS) {
            return TtsRequestFailure(
                "speech rate result=${rateResult.getOrNull()}",
                rateResult.exceptionOrNull()
            )
        }

        val listenerResult = runCatching {
            tts.setOnUtteranceProgressListener(progressListener)
        }
        if (listenerResult.isFailure || listenerResult.getOrNull() != TextToSpeech.SUCCESS) {
            return TtsRequestFailure(
                "progress listener result=${listenerResult.getOrNull()}",
                listenerResult.exceptionOrNull()
            )
        }
        return null
    }

    private fun queueChunks(
        tts: TextToSpeech,
        token: String,
        chunks: List<String>,
        utteranceIds: List<String>
    ): TtsRequestFailure? {
        for ((index, chunk) in chunks.withIndex()) {
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result: Result<Int>? = synchronized(engineCommandLock) {
                if (!isSpeechActive(token)) null
                else runCatching { tts.speak(chunk, queueMode, null, utteranceIds[index]) }
            }
            if (result == null) return null
            val returnCode = result.getOrNull()
            if (result.isFailure || returnCode != TextToSpeech.SUCCESS) {
                return TtsRequestFailure(
                    "chunk ${index + 1}/${chunks.size} result=$returnCode",
                    result.exceptionOrNull()
                )
            }
        }
        return null
    }

    private suspend fun recreateEngine(staleEngine: TextToSpeech): TextToSpeech? {
        discardEngine(staleEngine)
        return ensureEngine()
    }

    private fun discardEngine(staleEngine: TextToSpeech) {
        val discarded = synchronized(engineStateLock) {
            if (engine !== staleEngine) return@synchronized false
            engineGeneration += 1
            initialized = false
            engine = null
            true
        }
        if (!discarded) return
        synchronized(engineCommandLock) {
            runCatching { staleEngine.stop() }
            runCatching { staleEngine.shutdown() }
        }
    }

    private suspend fun ensureEngine(): TextToSpeech? = engineInitMutex.withLock {
        synchronized(engineStateLock) {
            if (initialized) return@withLock engine
        }
        val generation = synchronized(engineStateLock) { engineGeneration }
        suspendCancellableCoroutine { continuation ->
            var candidate: TextToSpeech? = null
            try {
                candidate = TextToSpeech(context) { status ->
                    val ready = candidate
                    val accepted = synchronized(engineStateLock) {
                        if (status == TextToSpeech.SUCCESS &&
                            ready != null &&
                            generation == engineGeneration
                        ) {
                            engine = ready
                            initialized = true
                            true
                        } else {
                            false
                        }
                    }
                    if (!accepted) runCatching { ready?.shutdown() }
                    if (continuation.isActive) {
                        continuation.resume(if (accepted) ready else null)
                    }
                }
                continuation.invokeOnCancellation {
                    val abandoned = candidate
                    synchronized(engineStateLock) {
                        if (generation == engineGeneration) engineGeneration += 1
                        if (engine === abandoned) {
                            engine = null
                            initialized = false
                        }
                    }
                    runCatching { abandoned?.shutdown() }
                }
            } catch (error: Throwable) {
                Timber.w(error, "TTS engine construction failed")
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private fun beginPendingStart(): Long = synchronized(speechStateLock) {
        val generation = speechControlGeneration
        pendingStartsByGeneration[generation] =
            pendingStartsByGeneration.getOrDefault(generation, 0) + 1
        generation
    }

    private fun endPendingStart(generation: Long) {
        synchronized(speechStateLock) {
            val remaining = pendingStartsByGeneration.getOrDefault(generation, 0) - 1
            if (remaining > 0) pendingStartsByGeneration[generation] = remaining
            else pendingStartsByGeneration.remove(generation)
        }
    }

    private fun isStartGenerationCurrent(generation: Long): Boolean =
        synchronized(speechStateLock) { generation == speechControlGeneration }

    private fun isSpeechActive(token: String): Boolean = synchronized(speechStateLock) {
        activeSpeech?.token == token
    }

    private fun isUtteranceActive(token: String, utteranceId: String): Boolean =
        synchronized(speechStateLock) {
            val speech = activeSpeech
            speech?.token == token && utteranceId in speech.remainingUtteranceIds
        }

    private fun replaceActiveUtteranceIds(token: String, utteranceIds: List<String>): Boolean =
        synchronized(speechStateLock) {
            val speech = activeSpeech ?: return@synchronized false
            if (speech.token != token) return@synchronized false
            speech.remainingUtteranceIds.clear()
            speech.remainingUtteranceIds.addAll(utteranceIds)
            true
        }

    private fun completeChunk(token: String, utteranceId: String) {
        val completed = synchronized(speechStateLock) {
            val speech = activeSpeech ?: return
            if (speech.token != token) return
            if (!speech.remainingUtteranceIds.remove(utteranceId)) return
            if (speech.remainingUtteranceIds.isNotEmpty()) return
            activeSpeech = null
            speech
        }
        abandonAudioFocus(token)
        deliverSuccess(completed)
    }

    private fun failActiveSpeech(error: Throwable, stopEngine: Boolean) {
        val speech = detachActiveSpeech() ?: return
        if (stopEngine) stopEngineSafely()
        abandonAudioFocus(speech.token)
        deliverError(speech, error)
    }

    private fun failSpeech(token: String, error: Throwable, stopEngine: Boolean) {
        val speech = detachActiveSpeech(token) ?: return
        if (stopEngine) stopEngineSafely()
        abandonAudioFocus(token)
        deliverError(speech, error)
    }

    private fun detachActiveSpeech(token: String? = null): ActiveSpeech? =
        synchronized(speechStateLock) {
            val speech = activeSpeech ?: return@synchronized null
            if (token != null && speech.token != token) return@synchronized null
            activeSpeech = null
            speech
        }

    /** Cancel active speech and any request still waiting to enter setup. */
    private fun invalidateSpeechRequests(): ActiveSpeech? = synchronized(speechStateLock) {
        speechControlGeneration += 1
        pendingStartsByGeneration.clear()
        activeSpeech.also { activeSpeech = null }
    }

    private fun stopEngineSafely() {
        synchronized(engineCommandLock) {
            runCatching { engine?.stop() }
        }
    }

    private fun deliverSuccess(speech: ActiveSpeech) {
        runCatching { speech.onDone() }
            .onFailure { Timber.w(it, "TTS completion callback failed") }
    }

    private fun deliverError(speech: ActiveSpeech, error: Throwable) {
        val callback = speech.onError
        if (callback != null) {
            runCatching { callback(error) }
                .onFailure { Timber.w(it, "TTS error callback failed") }
        } else {
            // Existing callers only supplied onDone. Preserve their queue-
            // advancement behavior until they opt into the richer error API.
            deliverSuccess(speech)
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
        private const val DEFAULT_MAX_SPEECH_INPUT_LENGTH = 4_000
        private const val MAX_TTS_REQUEST_RETRIES = 1

        private fun utteranceIds(token: String, attempt: Int, chunkCount: Int): List<String> =
            List(chunkCount) { index -> "$token:$attempt:$index" }

        internal fun shouldRetryRejectedRequest(attempt: Int): Boolean =
            attempt < MAX_TTS_REQUEST_RETRIES

        /**
         * Split long replies within Android's per-utterance limit. Prefer a
         * sentence ending in the latter half of the window, then whitespace,
         * and only hard-split when a single token exceeds the platform limit.
         */
        internal fun chunkText(text: String, maxLength: Int): List<String> {
            require(maxLength > 0) { "maxLength must be positive" }
            var remaining = text.trim()
            if (remaining.isEmpty()) return emptyList()
            val chunks = mutableListOf<String>()
            while (remaining.length > maxLength) {
                val minimumNaturalBreak = (maxLength / 2).coerceAtLeast(1)
                val sentenceCut = (maxLength - 1 downTo minimumNaturalBreak - 1)
                    .firstOrNull { index ->
                        remaining[index] in SENTENCE_ENDINGS &&
                            (index == remaining.lastIndex || remaining[index + 1].isWhitespace())
                    }
                    ?.plus(1)
                val whitespaceCut = (maxLength - 1 downTo 1)
                    .firstOrNull { remaining[it].isWhitespace() }
                val cut = sentenceCut ?: whitespaceCut ?: maxLength
                val chunk = remaining.substring(0, cut).trim()
                if (chunk.isNotEmpty()) chunks += chunk
                remaining = remaining.substring(cut).trimStart()
            }
            if (remaining.isNotEmpty()) chunks += remaining
            return chunks
        }

        private val SENTENCE_ENDINGS = setOf('.', '!', '?', ';', ':', '\n')

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
