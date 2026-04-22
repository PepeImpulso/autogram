package com.autogram.app.infrastructure.suggestion

import com.autogram.app.domain.model.SummaryEngineType
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks the user-configured OpenAI-compatible LLM endpoint for up to 3 short
 * canned replies given an incoming message. Reuses the same endpoint config
 * the summary engine reads — one toggle, one URL, one key, two features.
 *
 * Returns an empty list when:
 *   - the user hasn't picked LLM_ENDPOINT as their summary engine, OR
 *   - the network call fails / the response isn't a clean JSON array of strings.
 *
 * The caller (SuggestQuickReplyUseCase) treats an empty list as "fall back to
 * regex rules" — the LLM is purely additive, never replaces a working baseline.
 */
@Singleton
class LlmQuickReplySuggester @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val httpClient: OkHttpClient
) {

    suspend fun suggest(incomingText: String, limit: Int = 3): List<String> =
        withContext(Dispatchers.IO) {
            val s = settingsRepository.get()
            // Gate on the same toggle as summary so the user only configures one
            // endpoint. Activating "Summarize long messages" with LLM_ENDPOINT
            // automatically opts them into LLM-powered chip suggestions too.
            if (s.summaryEngine != SummaryEngineType.LLM_ENDPOINT) return@withContext emptyList()
            val endpoint = s.summaryLlmEndpoint.trim()
            val apiKey = s.summaryLlmApiKey
            val model = s.summaryLlmModel.trim().ifBlank { "gpt-4o-mini" }
            if (endpoint.isBlank()) return@withContext emptyList()
            val url = if (endpoint.endsWith("/chat/completions")) endpoint
                      else endpoint.trimEnd('/') + "/chat/completions"
            val parsed = url.toHttpUrlOrNull()
            if (parsed == null || (parsed.scheme != "http" && parsed.scheme != "https")) {
                Timber.w("LLM suggest endpoint invalid — falling back to regex")
                return@withContext emptyList()
            }

            val systemPrompt = buildString {
                append("You suggest short canned replies for a chat message. ")
                append("Output ONLY a JSON array of $limit short reply strings (max 5 words each), ")
                append("no preamble, no markdown, no explanation. ")
                append("Match the language of the input. ")
                append("Replies must be natural, immediately sendable, and varied (don't repeat the same intent).")
            }
            val payload = JSONObject()
                .put("model", model)
                .put("temperature", 0.4)
                .put("max_tokens", 120)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", incomingText))
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
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.w("LLM suggest HTTP ${response.code}")
                        return@use emptyList()
                    }
                    val bodyStr = response.body?.string().orEmpty()
                    val content = JSONObject(bodyStr).optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")
                        ?.trim()
                        .orEmpty()
                    parseStringArray(content, limit)
                }
            } catch (e: Exception) {
                Timber.w(e, "LLM suggest failed; falling back to regex")
                emptyList()
            }
        }

    /**
     * Tolerant JSON-array parser. LLMs sometimes wrap the array in markdown
     * fences or chat-style preambles, so we strip common decorations before
     * trying to parse — a small bit of leniency saves a fallback to regex
     * when the model is otherwise on-topic.
     */
    internal fun parseStringArray(raw: String, limit: Int): List<String> {
        if (raw.isBlank()) return emptyList()
        val cleaned = raw
            .removePrefix("```json").removePrefix("```").removeSuffix("```")
            .trim()
        // Try strict JSON first, then locate the first '[' ... ']' bracket pair.
        val candidate = if (cleaned.startsWith("[")) cleaned
                        else cleaned.substringAfter("[", "").let { if (it.isEmpty()) "" else "[$it" }
                            .substringBeforeLast("]", "").let { if (it.isEmpty()) "" else "$it]" }
        if (candidate.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(candidate)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optString(i).trim()
                if (item.isNotBlank()) out += item
                if (out.size >= limit) break
            }
            out
        } catch (e: Exception) {
            Timber.w("LLM suggest: response was not a JSON array — ignoring")
            emptyList()
        }
    }
}
