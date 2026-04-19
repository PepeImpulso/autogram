package com.autogram.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class AutogramApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // BUG-34: Plant a release tree so errors are logged in production
            Timber.plant(ReleaseTree())
        }
        Timber.d("Autogram initialized")
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
