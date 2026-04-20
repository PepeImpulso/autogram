package com.autogram.app.infrastructure.platform

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads an APK from the given URL via the system DownloadManager (progress
 * notification + retries + disk space handled by the OS) and launches the
 * install intent when done.
 */
@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context
) {

    sealed class InstallEvent {
        data class Started(val downloadId: Long) : InstallEvent()
        data class Completed(val apkFile: File) : InstallEvent()
        data class Failed(val reason: String) : InstallEvent()
    }

    private val events = Channel<InstallEvent>(Channel.BUFFERED)
    fun events(): Flow<InstallEvent> = events.receiveAsFlow()

    private var currentReceiver: BroadcastReceiver? = null

    /**
     * Enqueue a download and register a broadcast receiver that will fire the
     * install intent when the APK lands. Returns the download id immediately.
     */
    fun downloadAndInstall(apkUrl: String, filename: String) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        // Clean up any previous APK with the same filename to avoid "Unable to
        // open APK" errors when DownloadManager can't overwrite.
        val outFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), filename)
        if (outFile.exists()) outFile.delete()

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Autogram $filename")
            .setDescription("Descargando actualización…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, filename)
            .setMimeType("application/vnd.android.package-archive")

        val id = dm.enqueue(request)
        events.trySend(InstallEvent.Started(id))

        // Register a one-shot receiver for this download id.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (completedId != id) return
                try {
                    val apkFile = resolveDownloadedFile(dm, completedId, outFile)
                    if (apkFile == null) {
                        events.trySend(InstallEvent.Failed("Descarga fallida"))
                        return
                    }
                    events.trySend(InstallEvent.Completed(apkFile))
                    launchInstall(apkFile)
                } catch (e: Exception) {
                    Timber.e(e, "UpdateInstaller: onReceive")
                    events.trySend(InstallEvent.Failed(e.message ?: "Error desconocido"))
                } finally {
                    try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                    currentReceiver = null
                }
            }
        }
        currentReceiver = receiver
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun resolveDownloadedFile(dm: DownloadManager, id: Long, fallback: File): File? {
        val query = DownloadManager.Query().setFilterById(id)
        dm.query(query).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val localUriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else -1
                if (status != DownloadManager.STATUS_SUCCESSFUL) return null
                if (localUriIdx >= 0) {
                    val uriStr = cursor.getString(localUriIdx) ?: return null
                    val path = Uri.parse(uriStr).path ?: return null
                    val f = File(path)
                    if (f.exists()) return f
                }
            }
        }
        return if (fallback.exists()) fallback else null
    }

    private fun launchInstall(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "UpdateInstaller: launchInstall failed")
            events.trySend(InstallEvent.Failed("No se pudo iniciar la instalación: ${e.message}"))
        }
    }
}
