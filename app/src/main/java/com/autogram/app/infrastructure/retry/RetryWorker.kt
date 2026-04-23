package com.autogram.app.infrastructure.retry

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.autogram.app.domain.usecase.OutboxRetryUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Drains the outbox — messages in state QUEUED_OFFLINE or FAILED — whenever
 * network + TDLib auth come back. Triggered automatically:
 *
 *   - Once, at process start via [enqueueOnce], so pending messages from the
 *     previous session go out as soon as the app opens.
 *   - By [enqueueOnce] again whenever a new QUEUED_OFFLINE message is written
 *     (the use case calls it after persistQueuedOffline).
 *   - By WorkManager's own network-restored trigger via setConstraints().
 *
 * The actual decision logic lives in [OutboxRetryUseCase] so it can be
 * unit-tested without a Context. This class is a thin WorkManager shell
 * that just delegates and maps the use case's Outcome to a Worker Result.
 */
@HiltWorker
class RetryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val outboxRetryUseCase: OutboxRetryUseCase
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return when (outboxRetryUseCase.runOnce()) {
            OutboxRetryUseCase.Outcome.SUCCESS -> Result.success()
            OutboxRetryUseCase.Outcome.RETRY -> Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "outbox_retry"

        /**
         * Kick a one-shot attempt. Call after enqueueing a QUEUED_OFFLINE message
         * or from Application.onCreate to drain pending outbox on launch.
         */
        fun enqueueOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<RetryWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS
                )
                .build()
            // KEEP: if a retry worker is already queued, don't
            // schedule another. Each worker drains ALL outbox
            // messages in one run, so appending N times (one per
            // quick-fire-offline voice message) just wastes
            // battery and wakes the device N times for no reason.
            // Previously APPEND_OR_REPLACE, which did exactly that.
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
            Timber.d("RetryWorker enqueued")
        }
    }
}
