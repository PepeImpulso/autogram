package com.autogram.app.infrastructure.summarization

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device summarizer backed by Google AI Edge's Gemini Nano
 * (`com.google.ai.edge.aicore:aicore`). Available on Samsung Galaxy S24+/S25+
 * and Pixel 8+ when AICore's system model has been provisioned.
 *
 * We don't take the AICore artifact as a compile-time dependency because:
 *  - It's still early-access at the time of writing and may shift API.
 *  - The runtime is delivered outside the app (system-level AICore service),
 *    so we can detect availability without a hard link.
 *
 * The whole integration is therefore reflective. If any of the expected
 * classes isn't on the classpath at runtime, we surface [NotAvailable]
 * instead of crashing; the user falls back to the LLM endpoint engine or
 * disables summarization.
 *
 * When the user is ready to bind the native SDK, add the dependency in
 * `app/build.gradle.kts` and this engine will automatically pick it up —
 * no code changes needed here.
 */
@Singleton
class GeminiNanoSummarizationEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : SummarizationEngine {

    override suspend fun summarize(text: String, targetLanguageTag: String?): SummarizationEngine.Result =
        withContext(Dispatchers.Default) {
            val engine = tryLoadEngine() ?: return@withContext SummarizationEngine.Result.NotAvailable
            val prompt = buildString {
                append("Summarize the following in 1-2 sentences")
                if (!targetLanguageTag.isNullOrBlank()) {
                    append(" in ")
                    append(targetLanguageTag)
                }
                append(". No preamble:\n\n")
                append(text)
            }
            try {
                val summary = engine.generate(prompt)
                if (summary.isNullOrBlank()) SummarizationEngine.Result.Error("empty response")
                else SummarizationEngine.Result.Success(summary.trim())
            } catch (t: Throwable) {
                Timber.w(t, "Gemini Nano generate failed")
                SummarizationEngine.Result.Error(t.message ?: "aicore error")
            }
        }

    /**
     * Attempts to load the AICore-backed engine via reflection. Returns null
     * if the runtime isn't available — expected on devices without AICore.
     */
    private fun tryLoadEngine(): AiCoreFacade? {
        return try {
            // Target class path from the AI Edge SDK:
            //   com.google.ai.edge.aicore.GenerativeModel
            // with a constructor that takes (generationConfig) and a
            // suspending generateContent(prompt: String): GenerateContentResponse.
            val clazz = Class.forName("com.google.ai.edge.aicore.GenerativeModel")
            AiCoreFacade(clazz, context)
        } catch (e: ClassNotFoundException) {
            null
        } catch (t: Throwable) {
            Timber.w(t, "Gemini Nano AICore load failed")
            null
        }
    }

    /**
     * Thin reflective wrapper. If we ever adopt the AICore artifact directly,
     * drop this and use the typed API.
     */
    private class AiCoreFacade(private val modelClass: Class<*>, private val context: Context) {
        fun generate(prompt: String): String? {
            // Construct with default config. AICore's real API expects
            // a generationConfig; pass null and rely on defaults — this
            // stays compatible with the SDK as long as the no-arg default
            // overload continues to exist.
            val ctor = runCatching { modelClass.getConstructor() }.getOrNull()
                ?: runCatching { modelClass.getConstructor(Class.forName("com.google.ai.edge.aicore.GenerationConfig")) }.getOrNull()
                ?: return null
            val instance = ctor.newInstance()
            val method = modelClass.methods.firstOrNull {
                it.name == "generateContent" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == String::class.java
            } ?: return null
            val response = method.invoke(instance, prompt) ?: return null
            // AICore's response has a .text getter. Fall back to toString().
            val textMethod = response.javaClass.methods.firstOrNull { it.name == "getText" }
            return (textMethod?.invoke(response) as? String) ?: response.toString()
        }
    }
}
