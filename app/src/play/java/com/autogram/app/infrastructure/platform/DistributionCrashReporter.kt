package com.autogram.app.infrastructure.platform

import android.app.Application
import java.io.File

/** Google Play builds deliberately contain no third-party crash reporter. */
object DistributionCrashReporter {
    val isConfigured: Boolean = false

    fun initialize(@Suppress("UNUSED_PARAMETER") application: Application): Boolean = false

    fun captureManualReport(@Suppress("UNUSED_PARAMETER") logFile: File?): Result<Unit> =
        Result.failure(UnsupportedOperationException("Crash reporting is not packaged in this build"))
}
