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
            // REPLACE (not KEEP, not APPEND_OR_REPLACE):
            //
            // KEEP alone looked right — one worker drains the whole
            // outbox — but it silently delayed fresh messages
            // enqueued while a previous worker was in BackoffPolicy
            // EXPONENTIAL (up to 5 hours at cap). A new voice
            // message arriving after a flaky-network attempt would
            // wait hours for the next retry window.
            //
            // APPEND_OR_REPLACE stacks workers — N messages = N
            // redundant wakeups.
            //
            // REPLACE cancels any scheduled-with-backoff worker and
            // reschedules from scratch. The trade-off is that a
            // currently-RUNNING worker also gets cancelled mid-drain,
            // which could strand a row in MessageState.SENDING. We
            // recover from that in OutboxRetryUseCase.runOnce() by
            // un-stucking any SENDING rows back to QUEUED_OFFLINE
            // before draining on each startup.
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            Timber.d("RetryWorker enqueued")
        }
    }
}
