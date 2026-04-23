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
            // APPEND_OR_REPLACE:
            //
            // We rotated through three policies auditing this code.
            // Each has a real trade-off:
            //
            // - KEEP  — deduplicates (good) but inherits a stale
            //           BackoffPolicy.EXPONENTIAL timer from a
            //           previous failed attempt (up to 5h at cap).
            //           A fresh offline message would wait hours.
            //
            // - REPLACE — cancels the scheduled backoff (great) but
            //           ALSO cancels a currently-running worker mid
            //           -`tdLibClient.send(...)`. TDLib's native
            //           upload does NOT abort on coroutine cancel —
            //           it continues in the background and delivers
            //           the voice note anyway. Our row stays in
            //           SENDING, `resetStuckSending` flips it back to
            //           QUEUED_OFFLINE, the next worker re-sends,
            //           and the user's chat ends up with the message
            //           DUPLICATED. That's worse than the 5h stall.
            //
            // - APPEND_OR_REPLACE — running worker isn't cancelled
            //           (it finishes draining), new work appends
            //           after. Fresh backoff only kicks in for a
            //           REALLY persistent failure chain, and
            //           WorkManager restarts the worker as soon as
            //           the NetworkType.CONNECTED constraint is
            //           satisfied, bypassing part of the backoff.
            //
            // Stuck-SENDING recovery (`resetStuckSending` in the
            // worker's startup) is still useful — it cleans up
            // process-kill scenarios where TDLib died alongside our
            // coroutine.
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
            Timber.d("RetryWorker enqueued")
        }
    }
}
