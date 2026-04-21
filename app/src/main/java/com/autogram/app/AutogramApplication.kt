package com.autogram.app

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.infrastructure.platform.LocaleHelper
import com.autogram.app.infrastructure.platform.PendingReplyNotifier
import com.autogram.app.infrastructure.retry.RetentionCleanupWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class AutogramApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settingsRepository: SettingsRepository

    private val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun attachBaseContext(base: Context) {
        // Apply the saved language *synchronously* via SharedPreferences
        // before any resource lookup happens. The DB-backed settings row is
        // mirrored to LocaleHelper prefs so we never need to block on Room.
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

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

        // Apply saved UI language before any activity inflates its layout.
        // Reading from the DB is suspend so we launch a coroutine — activities
        // that need to apply the locale before first draw should also call
        // AppCompatDelegate.getApplicationLocales() defensively.
        appScope.launch {
            applyStoredLocale()
        }

        PendingReplyNotifier.ensureChannel(this)
        RetentionCleanupWorker.schedule(this)
    }

    private suspend fun applyStoredLocale() {
        val dbTag = runCatching { settingsRepository.get().languageTag }.getOrNull().orEmpty()
        val prefsTag = LocaleHelper.savedTag(this)
        // First launch after upgrading from a version without LocaleHelper prefs:
        // the DB still holds the choice but the SharedPreferences mirror is empty.
        // Seed it so the next attachBaseContext picks up the saved language.
        if (dbTag.isNotBlank() && prefsTag.isBlank()) {
            LocaleHelper.saveTag(this, dbTag)
        }
        val effective = if (dbTag.isNotBlank()) dbTag else prefsTag
        val locales = if (effective.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()  // follow system
        } else {
            LocaleListCompat.forLanguageTags(effective)
        }
        // Must be called from the main thread per AppCompat's contract.
        android.os.Handler(mainLooper).post {
            AppCompatDelegate.setApplicationLocales(locales)
        }
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
