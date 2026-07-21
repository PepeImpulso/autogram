package com.autogram.app.infrastructure.summarization

import com.autogram.app.BuildConfig
import com.autogram.app.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Summarizer backed by a user-configured OpenAI-compatible endpoint. Works
 * with the official OpenAI API, Anthropic's OpenAI-compatibility shim, Ollama
 * (`http://…:11434/v1/chat/completions`), Groq, LM Studio, LocalAI — anything
 * that speaks the `/v1/chat/completions` shape.
 *
 * We speak only the minimum: system + user messages, no function calls, no
 * streaming. Response shape: `choices[0].message.content`. The model name
 * comes from the user's settings; the prompt instructs a 1-2 sentence
 * summary in the target language.
 *
 * The endpoint + API key never make it into logs. Timber errors only
 * reference the HTTP code and the engine name.
 */
@Singleton
class LlmEndpointSummarizationEngine @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val httpClient: OkHttpClient
) : SummarizationEngine {

    override suspend fun summarize(text: String, targetLanguageTag: String?): SummarizationEngine.Result =
        withContext(Dispatchers.IO) {
            val s = settingsRepository.get()
            val endpoint = s.summaryLlmEndpoint.trim()
            val apiKey = s.summaryLlmApiKey
            val model = s.summaryLlmModel.trim().ifBlank { "gpt-4o-mini" }
            if (endpoint.isBlank()) {
                return@withContext SummarizationEngine.Result.NotAvailable
            }
            val url = if (endpoint.endsWith("/chat/completions")) endpoint
                      else endpoint.trimEnd('/') + "/chat/completions"
            val parsed = url.toHttpUrlOrNull()
            val allowedScheme = parsed?.scheme == "https" ||
                (BuildConfig.ALLOW_CLEARTEXT_LLM_ENDPOINT && parsed?.scheme == "http")
            if (parsed == null || !allowedScheme) {
                Timber.w("LLM summary endpoint invalid (scheme or parse)")
                return@withContext SummarizationEngine.Result.Error("invalid endpoint")
            }

            val systemPrompt = buildString {
                append("You are a summarizer. Output a 1-2 sentence summary only. ")
                append("No preamble, no meta-commentary, no quotes. ")
                val tag = targetLanguageTag?.takeIf { it.isNotBlank() }
                if (tag != null) {
                    val name = Locale.forLanguageTag(tag).displayLanguage.ifBlank { tag }
                    append("CRITICAL: write the summary in $name (locale $tag), ")
                    append("regardless of the input's language. ")
                    append("Translate if necessary; the summary MUST be in $name.")
                } else {
                    append("Write the summary in the same language as the input.")
                }
            }
            val payload = JSONObject()
                .put("model", model)
                .put("temperature", 0.2)
                .put("max_tokens", 160)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", text))
                )

            val headers = Headers.Builder().apply {
                add("Content-Type", "application/json")
                if (apiKey.isNotBlank()) add("Authorization", "Bearer $apiKey")
            }.build()

            val request = Request.Builder()
                .url(url)
                .headers(headers)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            try {
                // Hard cap on the whole call. The shared OkHttpClient
                // is configured with a generic connect/read timeout
                // but no overall call timeout; a dribbling endpoint
                // (1 byte/sec, or a stalled streaming server) would
                // hold the coroutine forever. 30s is well above any
                // reasonable chat-completions round-trip.
                kotlinx.coroutines.withTimeoutOrNull(LLM_CALL_TIMEOUT_MS) {
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Timber.w("LLM summary HTTP ${response.code}")
                            return@withTimeoutOrNull SummarizationEngine.Result.Error("HTTP ${response.code}")
                        }
                        // Bound the response body we're willing to
                        // parse. A misconfigured or hostile endpoint
                        // could stream gigabytes; `body.string()` has
                        // no implicit cap and would OOM the process.
                        val source = response.body?.source()
                        val bodyStr = if (source == null) {
                            ""
                        } else {
                            source.request(MAX_RESPONSE_BYTES)
                            val available = minOf(source.buffer.size, MAX_RESPONSE_BYTES)
                            source.buffer.readUtf8(available)
                        }
                        val obj = JSONObject(bodyStr)
                        val content = obj.optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.optString("content")
                            ?.trim()
                            .orEmpty()
                        if (content.isBlank()) {
                            SummarizationEngine.Result.Error("empty response")
                        } else {
                            SummarizationEngine.Result.Success(content)
                        }
                    }
                } ?: SummarizationEngine.Result.Error("LLM call timed out")
            } catch (e: Exception) {
                Timber.w(e, "LLM summary failed")
                SummarizationEngine.Result.Error(e.message ?: "network error")
            }
        }

    companion object {
        private const val LLM_CALL_TIMEOUT_MS: Long = 30_000L
        // 256KB — a chat-completions reply is typically a few KB;
        // anything above this is either a misconfigured stream or a
        // hostile endpoint. Defensive limit that protects the process
        // without cutting off any realistic summary.
        private const val MAX_RESPONSE_BYTES: Long = 256L * 1024L
    }
}
