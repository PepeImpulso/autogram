package com.autogram.app

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.infrastructure.audio.TtsPlayer
import com.autogram.app.infrastructure.platform.LocaleHelper
import com.autogram.app.infrastructure.platform.PendingReplyNotifier
import com.autogram.app.infrastructure.retry.RetentionCleanupWorker
import com.autogram.app.infrastructure.retry.RetryWorker
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import io.sentry.android.timber.SentryTimberIntegration
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
    @Inject lateinit var ttsPlayer: TtsPlayer

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
        // Crash reporting via Sentry. If SENTRY_DSN is empty (dev box without
        // the key in local.properties) we skip init and fall back to Log.e.
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isNotBlank()) {
            SentryAndroid.init(this) { options ->
                options.dsn = dsn
                options.environment = if (BuildConfig.DEBUG) "debug" else "release"
                options.release = "autogram@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
                // Ship Timber.e/.w straight into Sentry; e = exception, w = breadcrumb.
                options.addIntegration(
                    SentryTimberIntegration(
                        minEventLevel = SentryLevel.ERROR,
                        minBreadcrumbLevel = SentryLevel.INFO
                    )
                )
                // Attach screenshots/ANR context to crashes in release builds.
                options.isAttachScreenshot = !BuildConfig.DEBUG
                options.isAnrEnabled = true
                // PII scrubbing: the default integration sends user identifiers
                // (device id, locale) which we don't need. Also opt out of
                // automatically capturing the phone number / chat id that can
                // leak into TDLib error messages.
                options.isSendDefaultPii = false
                options.beforeSend = io.sentry.SentryOptions.BeforeSendCallback { event, _ ->
                    scrubSentryEvent(event); event
                }
                options.beforeBreadcrumb = io.sentry.SentryOptions.BeforeBreadcrumbCallback { crumb, _ ->
                    crumb.message = crumb.message?.let { scrubPii(it) }
                    crumb
                }
            }
            Timber.plant(Timber.DebugTree().takeIf { BuildConfig.DEBUG } ?: ReleaseTree())
        } else if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }
        Timber.d("Autogram initialized (sentry=${dsn.isNotBlank()})")

        // Apply saved UI language before any activity inflates its layout.
        // Reading from the DB is suspend so we launch a coroutine — activities
        // that need to apply the locale before first draw should also call
        // AppCompatDelegate.getApplicationLocales() defensively.
        appScope.launch {
            applyStoredLocale()
        }

        PendingReplyNotifier.ensureChannel(this)
        RetentionCleanupWorker.schedule(this)
        // Drain any messages left in the outbox from a previous process. Worker
        // is network-constrained so this is safe even if we're offline now.
        RetryWorker.enqueueOnce(this)
    }

    /**
     * Audit-HIGH-5: release the TextToSpeech engine when the system says we're
     * short on memory. `Application.onTerminate()` is only called in emulators,
     * so we hook onTrimMemory instead — fired on real devices when the system
     * backgrounds or is under pressure.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            runCatching { ttsPlayer.shutdown() }
        }
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

    companion object {
        // Patterns that match PII likely to sneak into log messages: phone
        // numbers, Telegram chat/message ids, long numeric tokens. We replace
        // in-place rather than dropping the event so the stack trace + code
        // path is still useful for debugging.
        private val PHONE_RE = Regex("""\+?\d[\d\s\-()]{7,}""")
        private val LONG_ID_RE = Regex("""\b-?\d{9,}\b""")

        internal fun scrubPii(s: String): String =
            s.replace(PHONE_RE, "[phone]").replace(LONG_ID_RE, "[id]")

        internal fun scrubSentryEvent(event: io.sentry.SentryEvent) {
            event.message?.let { m ->
                m.message = m.message?.let { scrubPii(it) }
                m.formatted = m.formatted?.let { scrubPii(it) }
            }
            // Drop anything user-identifiable that leaks in through integrations.
            event.user = null
            event.request = null
        }
    }
}
