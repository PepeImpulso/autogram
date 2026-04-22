package com.autogram.app.infrastructure.retry

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.domain.usecase.ManageHistoryUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.firstOrNull
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that:
 *  1. Purges messages older than `settings.retentionDays` from the DB.
 *  2. Sweeps `filesDir/audio_incoming/`, `filesDir/audio_outbox/` and
 *     `cacheDir/audio_outgoing/` for `.ogg` files that no longer appear
 *     in any `messages.audioFilePath` — these are orphans from crashes,
 *     uninstalls-of-referenced-services, or pre-1.4.0 installs.
 *
 * retentionDays <= 0 only disables (1), the orphan sweep still runs.
 */
@HiltWorker
class RetentionCleanupWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val manageHistoryUseCase: ManageHistoryUseCase
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val settings = settingsRepository.get()
            if (settings.retentionDays > 0) {
                val conversations = conversationRepository.getAll()
                for (conversation in conversations) {
                    manageHistoryUseCase.cleanupOldMessages(conversation.id, settings.retentionDays)
                }
                Timber.i("RetentionCleanupWorker: purged DB rows older than ${settings.retentionDays}d for ${conversations.size} conv(s)")
            }
            sweepOrphanAudios()
            repairMissingAudioPaths()
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "RetentionCleanupWorker failed")
            Result.retry()
        }
    }

    /**
     * Walk every INCOMING message with a non-null `audioFilePath` and, if the
     * file no longer exists on disk (user cleared cache, pruned with a file
     * manager, etc.), null the path out. The row stays so History still
     * shows "a voice note arrived at HH:MM" — we just stop advertising a
     * file that's gone, which would have shown a broken play button.
     */
    private suspend fun repairMissingAudioPaths() {
        // Room always emits at least an empty list, but guard with
        // firstOrNull to survive any future repository change that might
        // produce an empty cold flow.
        val all = messageRepository.observeAllAcrossConversations().firstOrNull().orEmpty()
        var repaired = 0
        for (msg in all) {
            val path = msg.audioFilePath ?: continue
            val f = File(path)
            if (!f.exists()) {
                messageRepository.update(msg.copy(audioFilePath = null))
                repaired++
            }
        }
        if (repaired > 0) {
            Timber.i("RetentionCleanupWorker: nulled $repaired missing audioFilePath(s)")
        }
    }

    /**
     * Collect every `audioFilePath` still referenced across the messages table
     * and delete any file in our audio directories not in that set.
     */
    private suspend fun sweepOrphanAudios() {
        // One snapshot is enough — grab first emission from the cold Flow.
        val all = messageRepository.observeAllAcrossConversations().firstOrNull().orEmpty()
        val known = all.mapNotNull { it.audioFilePath }.toSet()
        val dirs = listOf(
            File(context.filesDir, "audio_incoming"),
            File(context.filesDir, "audio_outbox"),
            File(context.cacheDir, "audio_outgoing")
        )
        var deleted = 0
        for (dir in dirs) {
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (!f.isFile) continue
                if (f.absolutePath !in known) {
                    if (f.delete()) deleted++
                }
            }
        }
        if (deleted > 0) {
            Timber.i("RetentionCleanupWorker: deleted $deleted orphan audio file(s)")
        }
    }

    companion object {
        const val UNIQUE_NAME = "retention_cleanup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RetentionCleanupWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
