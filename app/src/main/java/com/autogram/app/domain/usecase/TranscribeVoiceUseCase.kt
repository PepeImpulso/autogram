package com.autogram.app.domain.usecase

import androidx.appcompat.app.AppCompatDelegate
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.model.TranscriptionState
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.infrastructure.transcription.TranscriptionEngine
import timber.log.Timber
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transcribes an incoming voice note and writes the result back onto the
 * Message row. Idempotent — if a message already has a transcription or is
 * already in progress, the call is a no-op. Respects
 * [settings.transcribeIncomingVoice] so the feature is strictly opt-in.
 */
@Singleton
class TranscribeVoiceUseCase @Inject constructor(
    private val engine: TranscriptionEngine,
    private val messageRepository: MessageRepository,
    private val settingsRepository: SettingsRepository,
    private val summarizeMessageUseCase: SummarizeMessageUseCase
) {

    suspend fun transcribe(messageId: String) {
        val settings = settingsRepository.get()
        if (!settings.transcribeIncomingVoice) return
        val msg = messageRepository.getById(messageId) ?: return
        if (msg.transcription != null) return
        if (msg.transcriptionState == TranscriptionState.IN_PROGRESS ||
            msg.transcriptionState == TranscriptionState.DONE) return
        val path = msg.audioFilePath ?: return
        val file = File(path)
        if (!file.exists()) return

        messageRepository.update(msg.copy(transcriptionState = TranscriptionState.IN_PROGRESS))
        val locale = currentUiLocale(settings.languageTag)
        val result = runCatching { engine.transcribe(file, locale) }
            .getOrElse { e ->
                Timber.w(e, "Transcription engine threw for ${msg.id}")
                TranscriptionEngine.Result.Error(e.message ?: "engine crashed")
            }

        when (result) {
            is TranscriptionEngine.Result.Success -> {
                messageRepository.update(
                    msg.copy(
                        transcription = result.text,
                        transcriptionState = TranscriptionState.DONE
                    )
                )
                Timber.d("Transcribed ${msg.id} (${result.text.length} chars)")
                // Kick off summarization right after. The use case itself
                // checks the settings toggle + word-count threshold, so this
                // is cheap to always invoke.
                runCatching { summarizeMessageUseCase.summarize(msg.id) }
                    .onFailure { Timber.w(it, "Chained summarize threw") }
            }
            TranscriptionEngine.Result.NotInstalled ->
                messageRepository.update(msg.copy(transcriptionState = TranscriptionState.UNAVAILABLE))
            TranscriptionEngine.Result.LanguageUnavailable ->
                messageRepository.update(msg.copy(transcriptionState = TranscriptionState.UNAVAILABLE))
            is TranscriptionEngine.Result.Error -> {
                Timber.w("Transcription failed for ${msg.id}: ${result.message}")
                messageRepository.update(msg.copy(transcriptionState = TranscriptionState.FAILED))
            }
        }
    }

    private fun currentUiLocale(tag: String): Locale {
        if (tag.isNotBlank()) return Locale.forLanguageTag(tag)
        val appLocale = AppCompatDelegate.getApplicationLocales()
        // LocaleListCompat.get(0) on an empty list throws IOOBE, so gate on
        // isEmpty first and fall back to the platform default if nothing's set.
        if (appLocale.isEmpty) return Locale.getDefault()
        return appLocale[0] ?: Locale.getDefault()
    }
}
