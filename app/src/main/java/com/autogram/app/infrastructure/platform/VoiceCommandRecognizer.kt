package com.autogram.app.infrastructure.platform

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.autogram.app.domain.model.VoiceCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Wrapper sobre SpeechRecognizer que devuelve un VoiceCommand a partir
 * de la primera utterance. Sólo escucha mientras se invoca listenOnce().
 *
 * Limitación conocida: en Android Auto la política anti-distracción
 * puede bloquear la UI propia de SpeechRecognizer. En ese caso devuelve
 * VoiceCommand.UNKNOWN y registra un warning; la UI debe seguir
 * permitiendo control por botones.
 */
@Singleton
class VoiceCommandRecognizer @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    suspend fun listenOnce(language: String = "es-ES"): VoiceCommand {
        if (!isAvailable()) {
            Timber.w("VoiceCommandRecognizer: SpeechRecognizer no disponible en este dispositivo")
            return VoiceCommand.UNKNOWN
        }
        return suspendCancellableCoroutine { cont ->
            // SpeechRecognizer requires creation/listening on the main looper.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }

                var resumed = false
                fun finish(result: VoiceCommand) {
                    if (resumed) return
                    resumed = true
                    try { recognizer.destroy() } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(result)
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onError(error: Int) {
                        Timber.w("VoiceCommandRecognizer: error=$error")
                        finish(VoiceCommand.UNKNOWN)
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        val command = matches.asSequence()
                            .map { VoiceCommand.fromUtterance(it) }
                            .firstOrNull { it != VoiceCommand.UNKNOWN }
                            ?: VoiceCommand.UNKNOWN
                        Timber.d("VoiceCommandRecognizer: results=$matches → $command")
                        finish(command)
                    }
                })

                cont.invokeOnCancellation {
                    try { recognizer.cancel() } catch (_: Exception) {}
                    try { recognizer.destroy() } catch (_: Exception) {}
                }

                try {
                    recognizer.startListening(intent)
                } catch (e: SecurityException) {
                    Timber.w(e, "VoiceCommandRecognizer: SecurityException al arrancar (¿AA bloqueando?)")
                    finish(VoiceCommand.UNKNOWN)
                } catch (e: Exception) {
                    Timber.e(e, "VoiceCommandRecognizer: fallo al arrancar")
                    finish(VoiceCommand.UNKNOWN)
                }
            }
        }
    }
}
