package com.autogram.app.infrastructure.summarization

import com.autogram.app.domain.model.SummaryEngineType
import com.autogram.app.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dispatches summarization requests to whichever concrete engine the user has
 * selected in settings. Keeps the use case layer free of `when (engineType)`
 * branching.
 */
@Singleton
class SummarizationEngineRouter @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val geminiNano: GeminiNanoSummarizationEngine,
    private val llmEndpoint: LlmEndpointSummarizationEngine
) : SummarizationEngine {

    override suspend fun summarize(text: String, targetLanguageTag: String?): SummarizationEngine.Result {
        val settings = settingsRepository.get()
        if (!settings.summarizeLongMessages) return SummarizationEngine.Result.NotAvailable
        return when (settings.summaryEngine) {
            SummaryEngineType.GEMINI_NANO -> geminiNano.summarize(text, targetLanguageTag)
            SummaryEngineType.LLM_ENDPOINT -> llmEndpoint.summarize(text, targetLanguageTag)
            SummaryEngineType.NONE -> SummarizationEngine.Result.NotAvailable
        }
    }
}
