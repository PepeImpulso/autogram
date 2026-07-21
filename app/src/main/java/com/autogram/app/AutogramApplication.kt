package com.autogram.app

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.infrastructure.audio.TtsPlayer
import com.autogram.app.infrastructure.platform.DistributionCrashReporter
import com.autogram.app.infrastructure.platform.FileLoggingTree
import com.autogram.app.infrastructure.platform.LocaleHelper
import com.autogram.app.infrastructure.platform.PendingReplyNotifier
import com.autogram.app.infrastructure.retry.RetentionCleanupWorker
import com.autogram.app.infrastructure.retry.RetryWorker
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
    @Inject lateinit var playReplyUseCase: PlayReplyUseCase
    @Inject lateinit var mediaPlayback: com.autogram.app.service.AutogramMediaPlaybackController

    // v1.24.2 — plant from onCreate, expose to the Settings UI so the user
    // can share / clear logs from inside the app. Initialised post-onCreate
    // so accesses before then return null (defensive).
    @Volatile
    var fileLoggingTree: FileLoggingTree? = null
        private set

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
        val crashReportingEnabled = DistributionCrashReporter.initialize(this)
        if (crashReportingEnabled) {
            Timber.plant(Timber.DebugTree().takeIf { BuildConfig.DEBUG } ?: ReleaseTree())
        } else if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }
        // v1.24.2 — file logging for release-mode in-car debugging.
        // Always-on (release + debug) so the user can flip on the share
        // button in Settings without needing a dev build.
        fileLoggingTree = FileLoggingTree(this)
        Timber.plant(fileLoggingTree!!)
        Timber.d(
            "Autogram initialized " +
                "(crashReporting=$crashReportingEnabled)"
        )

        // Apply saved UI language before any activity inflates its layout.
        // Reading from the DB is suspend so we launch a coroutine — activities
        // that need to apply the locale before first draw should also call
        // AppCompatDelegate.getApplicationLocales() defensively.
        appScope.launch {
            applyStoredLocale()
        }

        PendingReplyNotifier.ensureChannel(this)
        // v1.23.1 — pre-warm MediaSessionCompat on Main so its internal
        // Handler() constructor finds a Looper. Otherwise the first
        // access from playbackScope (Dispatchers.IO) crashed with
        // "Can't create handler inside thread … that has not called
        // Looper.prepare()" — see Sentry f871b17746fd408b87570cd7a4bf09c9
        // on Android 16 / Galaxy S24+.
        mediaPlayback.warmUp()
        appScope.launch { playReplyUseCase.restorePendingQueue() }
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
        // numbers and long numeric tokens (Telegram chat / message ids).
        // We replace in-place rather than dropping the event so the stack
        // trace + code path stays useful for debugging.
        //
        // Order matters: LONG_ID runs first because a pure-digit chat id
        // (e.g. "1234567890") would also match the lax phone regex. After
        // long IDs are masked the phone regex can only catch true phone-
        // shaped strings (must start with + or contain at least one
        // separator like space / dash / parens).
        //
        // Both regexes are anchored on \b so trailing whitespace and
        // punctuation are preserved — a previous version ate the space
        // after a phone match, producing "Call [phone]to verify" instead
        // of "Call [phone] to verify".
        private val LONG_ID_RE = Regex("""(?<![\d.])-?\d{9,}\b""")
        private val PHONE_RE = Regex("""\+\d[\d\s\-()]{6,}\d|\b\d{1,4}([\s\-()]+\d{2,4}){2,}\b""")

        internal fun scrubPii(s: String): String =
            s.replace(LONG_ID_RE, "[id]").replace(PHONE_RE, "[phone]")

    }
}
