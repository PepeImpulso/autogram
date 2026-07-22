package com.autogram.app.infrastructure.platform

import android.app.Application
import com.autogram.app.AutogramApplication
import com.autogram.app.BuildConfig
import io.sentry.Attachment
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.android.timber.SentryTimberIntegration
import java.io.File

/** Sentry adapter packaged exclusively in the standalone GitHub flavor. */
object DistributionCrashReporter {
    val isConfigured: Boolean
        get() = BuildConfig.SENTRY_DSN.isNotBlank() &&
            BuildConfig.ENABLE_AUTOMATIC_CRASH_REPORTING

    fun initialize(application: Application): Boolean {
        if (!isConfigured) return false
        SentryAndroid.init(application) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            options.environment = if (BuildConfig.DEBUG) "debug" else "release"
            options.release = "autogram@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            options.addIntegration(
                SentryTimberIntegration(
                    minEventLevel = SentryLevel.ERROR,
                    minBreadcrumbLevel = SentryLevel.INFO
                )
            )
            options.isAttachScreenshot =
                !BuildConfig.DEBUG && BuildConfig.ENABLE_SENTRY_SCREENSHOTS
            options.isAnrEnabled = true
            options.isSendDefaultPii = false
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                scrubEvent(event)
                event
            }
            options.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ ->
                breadcrumb.message = breadcrumb.message?.let { AutogramApplication.scrubPii(it) }
                breadcrumb
            }
        }
        return true
    }

    fun captureManualReport(logFile: File?): Result<Unit> = runCatching {
        check(isConfigured) { "Crash reporting is not configured" }
        Sentry.withScope { scope ->
            scope.setTag("manual_report", "true")
            scope.setTag("app_version", BuildConfig.VERSION_NAME)
            if (logFile?.exists() == true) {
                scope.addAttachment(
                    Attachment(logFile.absolutePath, "autogram.log", "text/plain")
                )
            }
            Sentry.captureMessage("Manual bug report from user", SentryLevel.INFO)
        }
    }

    private fun scrubEvent(event: SentryEvent) {
        event.message?.let { message ->
            message.message = message.message?.let { AutogramApplication.scrubPii(it) }
            message.formatted = message.formatted?.let { AutogramApplication.scrubPii(it) }
        }
        event.exceptions?.forEach { exception ->
            exception.value = exception.value?.let { AutogramApplication.scrubPii(it) }
        }
        event.breadcrumbs?.forEach { breadcrumb ->
            breadcrumb.message = breadcrumb.message?.let { AutogramApplication.scrubPii(it) }
            breadcrumb.data.forEach { (key, value) ->
                if (value is String) breadcrumb.setData(key, AutogramApplication.scrubPii(value))
            }
        }
        event.user = null
        event.request = null
    }
}
