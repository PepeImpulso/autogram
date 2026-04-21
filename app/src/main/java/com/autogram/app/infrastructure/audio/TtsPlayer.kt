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

    suspend fun speak(text: String, onDone: () -> Unit = {}) {
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
        val locale = currentLocaleOrDefault()
        runCatching { tts.language = locale }

        tts.setSpeechRate(settings.playbackSpeed.coerceIn(0.5f, 2.0f))
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        )

        val utteranceId = UUID.randomUUID().toString()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { onDone() }
            @Deprecated("Deprecated in API 21", ReplaceWith(""))
            override fun onError(id: String?) { onDone() }
            override fun onError(id: String?, errorCode: Int) { onDone() }
        })
        runCatching {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }.onFailure {
            Timber.w(it, "TtsPlayer.speak failed")
            onDone()
        }
    }

    fun stop() {
        runCatching { engine?.stop() }
    }

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
}
