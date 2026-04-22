package com.autogram.app.infrastructure.summarization

import com.autogram.app.domain.model.SummaryEngineType

/**
 * Pluggable abstraction for "condense this text into a short summary".
 *
 * Two implementations ship today, each behind a [SummaryEngineType]:
 *  - GEMINI_NANO: on-device Gemini Nano via the Google AI Edge SDK
 *    ([GeminiNanoSummarizationEngine]). The SDK is loaded by reflection so
 *    the app compiles even without the dependency and falls back to
 *    [Result.NotAvailable] when the runtime isn't present.
 *  - LLM_ENDPOINT: user-configured OpenAI-compatible HTTP endpoint
 *    ([LlmEndpointSummarizationEngine]).
 *
 * Caller inspects the sealed [Result]; the use case layer writes
 * [SummaryState] accordingly on the Message row.
 */
interface SummarizationEngine {

    sealed class Result {
        data class Success(val text: String) : Result()
        object NotAvailable : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun summarize(text: String, targetLanguageTag: String? = null): Result
}
