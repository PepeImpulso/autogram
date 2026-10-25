package com.autogram.app.infrastructure.platform

import android.content.Context
import androidx.core.content.FileProvider
import com.autogram.app.AutogramApplication
import android.net.Uri
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.24.2 — Timber tree that appends every Timber log line to a rolling
 * file under cacheDir/autogram-logs/. Lets the user pull the actual
 * runtime trace out of a release build without needing adb / Sentry —
 * critical when debugging issues that only reproduce in-car.
 *
 *   - Two rolling files (autogram.log + autogram.log.1). When the
 *     active file exceeds [MAX_BYTES] we rotate (rename current → .1,
 *     start fresh). Cap is 1 MB per file so the worst-case footprint
 *     is 2 MB.
 *   - Format is `YYYY-MM-DD HH:MM:SS.SSS  P/Tag: message` plus the
 *     full stack trace on throwables. Same TLV the user would see
 *     on Logcat.
 *   - Synchronised writes — Timber events can come from any thread,
 *     and PrintWriter isn't thread-safe by itself. Lock cost is
 *     negligible given the log volume.
 *
 * Plant from `AutogramApplication.onCreate()` alongside the existing
 * `DebugTree` / `ReleaseTree`. Surface the file path via [logFile]
 * for the Settings → "Send debug logs" share button.
 */
class FileLoggingTree(private val context: Context) : Timber.Tree() {

    private val baseDir: File by lazy {
        File(context.cacheDir, LOG_DIR).apply { mkdirs() }
    }

    private val active: File get() = File(baseDir, ACTIVE_NAME)
    private val rotated: File get() = File(baseDir, ROTATED_NAME)

    private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    private val lock = Any()

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Mirror DebugTree's prefix style so log files read like Logcat.
        val priorityLetter = when (priority) {
            android.util.Log.VERBOSE -> 'V'
            android.util.Log.DEBUG -> 'D'
            android.util.Log.INFO -> 'I'
            android.util.Log.WARN -> 'W'
            android.util.Log.ERROR -> 'E'
            android.util.Log.ASSERT -> 'A'
            else -> '?'
        }
        val line = buildString {
            append(tsFormat.format(Date()))
            append("  ")
            append(priorityLetter)
            append('/')
            append(tag ?: "Autogram")
            append(": ")
            append(message)
        }
        runCatching {
            synchronized(lock) {
                rotateIfNeeded()
                PrintWriter(FileWriter(active, true)).use { writer ->
                    writer.println(line)
                    if (t != null) {
                        t.printStackTrace(writer)
                    }
                    writer.flush()
                }
            }
        }
        // Intentionally swallow IOExceptions — never let logging itself
        // crash the app or recurse via Timber.e.
    }

    private fun rotateIfNeeded() {
        if (!active.exists()) return
        if (active.length() < MAX_BYTES) return
        runCatching {
            if (rotated.exists()) rotated.delete()
            active.renameTo(rotated)
        }
    }

    /**
     * Concatenated content of the rolling log files (rotated first, then
     * active) — what the share button hands off via FileProvider.
     * Returns a freshly-written, world-readable copy under cacheDir so
     * the receiving app (Gmail, etc.) can resolve the Uri.
     */
    fun materialiseExportFile(): File {
        val out = File(baseDir, EXPORT_NAME)
        synchronized(lock) {
            PrintWriter(FileWriter(out, false)).use { writer ->
                writer.println("# Autogram debug log export")
                writer.println("# generated=${tsFormat.format(Date())}")
                writer.println("# package=${context.packageName}")
                writer.println("# files=[$ROTATED_NAME, $ACTIVE_NAME]")
                writer.println()
                // Every exported line goes through the same PII scrubber as
                // Sentry events: this file leaves the device (Sentry
                // attachment or share sheet). The on-device files stay raw.
                if (rotated.exists()) {
                    writer.println("# ---- $ROTATED_NAME ----")
                    rotated.readLines().forEach { writer.println(scrubExportLine(it)) }
                }
                if (active.exists()) {
                    writer.println("# ---- $ACTIVE_NAME ----")
                    active.readLines().forEach { writer.println(scrubExportLine(it)) }
                }
            }
        }
        return out
    }

    /**
     * Returns a content:// Uri the share intent can hand to the
     * receiving app. Falls back to null if the FileProvider authority
     * can't expose the export file.
     */
    fun exportUri(): Uri? {
        val file = runCatching { materialiseExportFile() }.getOrNull() ?: return null
        return runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }.getOrNull()
    }

    /**
     * Manually wipe the log files (active + rotated + export). Called
     * from the Settings → "Clear debug logs" button so the user can
     * start a clean capture before a focused reproduction.
     */
    fun clear() {
        synchronized(lock) {
            runCatching { active.delete() }
            runCatching { rotated.delete() }
            runCatching { File(baseDir, EXPORT_NAME).delete() }
        }
    }

    companion object {
        private const val LOG_DIR = "autogram-logs"
        private const val ACTIVE_NAME = "autogram.log"
        private const val ROTATED_NAME = "autogram.log.1"
        private const val EXPORT_NAME = "autogram-export.log"
        // 1 MB per file × 2 rotation slots = 2 MB worst case.
        private const val MAX_BYTES: Long = 1L * 1024L * 1024L

        // The line timestamp ("2026-10-25 12:34:56.789") looks like a phone
        // number to the scrubber, so it is kept verbatim and only the rest
        // of the line is scrubbed.
        private val TIMESTAMP_PREFIX_RE =
            Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""")

        internal fun scrubExportLine(line: String): String {
            val prefix = TIMESTAMP_PREFIX_RE.find(line)?.value ?: ""
            return prefix + AutogramApplication.scrubPii(line.substring(prefix.length))
        }
    }
}
