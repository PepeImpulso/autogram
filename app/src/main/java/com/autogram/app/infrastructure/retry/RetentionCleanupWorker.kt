package com.autogram.app.infrastructure.retry

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.domain.usecase.ManageHistoryUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that purges messages older than `settings.retentionDays`
 * for every conversation. retentionDays <= 0 disables purging.
 */
@HiltWorker
class RetentionCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val conversationRepository: ConversationRepository,
    private val manageHistoryUseCase: ManageHistoryUseCase
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val settings = settingsRepository.get()
            if (settings.retentionDays <= 0) {
                Timber.d("RetentionCleanupWorker: retentionDays=${settings.retentionDays}, skipping")
                return Result.success()
            }
            val conversations = conversationRepository.getAll()
            for (conversation in conversations) {
                manageHistoryUseCase.cleanupOldMessages(conversation.id, settings.retentionDays)
            }
            Timber.i("RetentionCleanupWorker: purged messages older than ${settings.retentionDays} days across ${conversations.size} conversation(s)")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "RetentionCleanupWorker failed")
            Result.retry()
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
