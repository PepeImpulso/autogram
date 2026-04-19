package com.autogram.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.autogram.app.infrastructure.platform.PendingReplyNotifier
import com.autogram.app.infrastructure.retry.RetentionCleanupWorker
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class AutogramApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // BUG-34: Plant a release tree so errors are logged in production
            Timber.plant(ReleaseTree())
        }
        Timber.d("Autogram initialized")

        PendingReplyNotifier.ensureChannel(this)
        RetentionCleanupWorker.schedule(this)
    }

    /** BUG-34: Minimal release tree that logs errors to Log.e */
    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority >= android.util.Log.ERROR) {
                android.util.Log.e(tag, message, t)
            }
        }
    }
}
