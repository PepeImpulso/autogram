package com.autogram.app.domain.usecase

import androidx.appcompat.app.AppCompatDelegate
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.model.SummaryState
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.infrastructure.summarization.SummarizationEngine
import com.autogram.app.infrastructure.summarization.SummarizationEngineRouter
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces a short summary for a message if:
 *   - The user has enabled `summarizeLongMessages` and picked an engine.
 *   - The message has usable text (transcription or textContent).
 *   - That text crosses the "long" threshold in words.
 *
 * Idempotent. Writes `summaryState` through PENDING → IN_PROGRESS → DONE/FAILED/UNAVAILABLE.
 * Short messages end up in [SummaryState.NONE] and are not touched again unless the
 * transcription later grows (which it doesn't, but for future audio replacement
 * flows it's safe to re-invoke).
 */
@Singleton
class SummarizeMessageUseCase @Inject constructor(
    private val router: SummarizationEngineRouter,
    private val messageRepository: MessageRepository,
    private val settingsRepository: SettingsRepository
) {

    suspend fun summarize(messageId: String) {
        val settings = settingsRepository.get()
        if (!settings.summarizeLongMessages) return
        val msg = messageRepository.getById(messageId) ?: return
        // Skip if already done or in progress.
        if (msg.summary != null) return
        if (msg.summaryState == SummaryState.IN_PROGRESS ||
            msg.summaryState == SummaryState.DONE) return

        val text = msg.transcription?.takeIf { it.isNotBlank() }
            ?: msg.textContent?.takeIf { it.isNotBlank() }
            ?: return
        val wordCount = text.split(Regex("\\s+")).size
        if (wordCount < THRESHOLD_WORDS) return  // too short to bother summarizing

        messageRepository.update(msg.copy(summaryState = SummaryState.IN_PROGRESS))
        val targetTag = currentUiLocale(settings.languageTag).toLanguageTag()

        val result = runCatching { router.summarize(text, targetTag) }
            .getOrElse { e ->
                Timber.w(e, "Summarization router threw for ${msg.id}")
                SummarizationEngine.Result.Error(e.message ?: "router crashed")
            }

        when (result) {
            is SummarizationEngine.Result.Success ->
                messageRepository.update(
                    msg.copy(summary = result.text, summaryState = SummaryState.DONE)
                )
            SummarizationEngine.Result.NotAvailable ->
                messageRepository.update(msg.copy(summaryState = SummaryState.UNAVAILABLE))
            is SummarizationEngine.Result.Error -> {
                Timber.w("Summarization failed for ${msg.id}: ${result.message}")
                messageRepository.update(msg.copy(summaryState = SummaryState.FAILED))
            }
        }
    }

    private fun currentUiLocale(tag: String): Locale {
        if (tag.isNotBlank()) return Locale.forLanguageTag(tag)
        val appLocale = AppCompatDelegate.getApplicationLocales()
        if (appLocale.isEmpty) return Locale.getDefault()
        return appLocale[0] ?: Locale.getDefault()
    }

    companion object {
        private const val THRESHOLD_WORDS = 60
    }
}
