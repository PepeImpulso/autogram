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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        data class Progress(val downloadId: Long, val message: String) : InstallEvent()
        data class Completed(val apkFile: File) : InstallEvent()
        data class Failed(val reason: String) : InstallEvent()
    }

    private val events = Channel<InstallEvent>(Channel.BUFFERED)
    fun events(): Flow<InstallEvent> = events.receiveAsFlow()

    private var currentReceiver: BroadcastReceiver? = null
    private var currentDownloadId: Long? = null
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    /**
     * Enqueue a download and register a broadcast receiver that will fire the
     * install intent when the APK lands. Returns the download id immediately.
     */
    fun downloadAndInstall(apkUrl: String, filename: String) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        resetActiveDownload(removeDownload = true, dm = dm)

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

        // Register the broadcast receiver BEFORE enqueueing. A fast
        // download (tiny APK, cached DNS, disk-warm) can fire
        // ACTION_DOWNLOAD_COMPLETE before a receiver registered
        // afterwards has a chance to observe it — the event would
        // be dropped and the UI stuck on "Downloading" forever.
        //
        // The receiver is a one-shot: it self-unregisters in the
        // finally block once the matching download id fires.
        val pendingIdHolder = arrayOf(-1L)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (completedId != pendingIdHolder[0]) return
                try {
                    completeDownload(dm, completedId, outFile)
                } catch (e: Exception) {
                    Timber.e(e, "UpdateInstaller: onReceive")
                    failDownload(completedId, e.message ?: "Error desconocido")
                }
            }
        }
        currentReceiver = receiver
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // RECEIVER_NOT_EXPORTED: ACTION_DOWNLOAD_COMPLETE is a protected
            // system broadcast — the platform still delivers it to us — but
            // a third-party app cannot spoof it to confuse the updater.
            // Issue #70 (audit follow-up).
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        // Now safe to enqueue — the receiver will catch whatever
        // completion event fires, even immediately.
        val id = dm.enqueue(request)
        pendingIdHolder[0] = id
        synchronized(lock) { currentDownloadId = id }
        events.trySend(InstallEvent.Started(id))
        monitorJob = scope.launch {
            monitorDownload(dm, id, outFile)
        }
    }

    private fun completeDownload(dm: DownloadManager, id: Long, fallback: File) {
        val apkFile = resolveDownloadedFile(dm, id, fallback)
        if (apkFile == null) {
            failDownload(id, "Descarga fallida")
            return
        }
        val shouldHandle = synchronized(lock) {
            if (currentDownloadId != id) false
            else {
                currentDownloadId = null
                true
            }
        }
        if (!shouldHandle) return
        cleanupReceiver()
        monitorJob?.cancel()
        monitorJob = null
        events.trySend(InstallEvent.Completed(apkFile))
        launchInstall(apkFile)
    }

    private suspend fun monitorDownload(dm: DownloadManager, id: Long, fallback: File) {
        var lastDownloaded = -1L
        var lastStatus = -1
        var lastChange = System.currentTimeMillis()
        val started = lastChange

        while (currentCoroutineContext().isActive && synchronized(lock) { currentDownloadId == id }) {
            val snapshot = querySnapshot(dm, id)
            val now = System.currentTimeMillis()
            if (snapshot == null) {
                failDownload(id, "La descarga ya no está disponible. Vuelve a intentarlo.")
                return
            }

            if (snapshot.downloadedBytes != lastDownloaded || snapshot.status != lastStatus) {
                lastDownloaded = snapshot.downloadedBytes
                lastStatus = snapshot.status
                lastChange = now
            }

            when (snapshot.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    completeDownload(dm, id, fallback)
                    return
                }
                DownloadManager.STATUS_FAILED -> {
                    failDownload(id, failedReason(snapshot.reason))
                    return
                }
                DownloadManager.STATUS_PAUSED -> {
                    events.trySend(InstallEvent.Progress(id, pausedReason(snapshot.reason)))
                }
                DownloadManager.STATUS_PENDING -> {
                    events.trySend(InstallEvent.Progress(id, "Descarga en cola…"))
                }
                DownloadManager.STATUS_RUNNING -> {
                    events.trySend(InstallEvent.Progress(id, progressMessage(snapshot)))
                }
            }

            if (now - started > MAX_DOWNLOAD_MS) {
                failDownload(id, "La descarga tarda demasiado. Comprueba la conexión y vuelve a intentarlo.")
                return
            }
            if (now - lastChange > STALL_TIMEOUT_MS) {
                failDownload(id, "La descarga no avanza. Comprueba la conexión y vuelve a intentarlo.")
                return
            }
            delay(MONITOR_INTERVAL_MS)
        }
    }

    private fun failDownload(id: Long, reason: String) {
        val shouldHandle = synchronized(lock) {
            if (currentDownloadId != id) false
            else {
                currentDownloadId = null
                true
            }
        }
        if (!shouldHandle) return
        cleanupReceiver()
        monitorJob?.cancel()
        monitorJob = null
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching { dm.remove(id) }
        events.trySend(InstallEvent.Failed(reason))
    }

    private fun resetActiveDownload(removeDownload: Boolean, dm: DownloadManager) {
        val previousId = synchronized(lock) {
            val old = currentDownloadId
            currentDownloadId = null
            old
        }
        monitorJob?.cancel()
        monitorJob = null
        cleanupReceiver()
        if (removeDownload && previousId != null) {
            runCatching { dm.remove(previousId) }
        }
    }

    private fun cleanupReceiver() {
        currentReceiver?.let { old ->
            runCatching { context.unregisterReceiver(old) }
            currentReceiver = null
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

    private fun querySnapshot(dm: DownloadManager, id: Long): DownloadSnapshot? {
        val query = DownloadManager.Query().setFilterById(id)
        dm.query(query).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return null
            val status = cursor.getIntOrNull(DownloadManager.COLUMN_STATUS) ?: return null
            val reason = cursor.getIntOrNull(DownloadManager.COLUMN_REASON) ?: 0
            val downloaded = cursor.getLongOrNull(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR) ?: -1L
            val total = cursor.getLongOrNull(DownloadManager.COLUMN_TOTAL_SIZE_BYTES) ?: -1L
            return DownloadSnapshot(status, reason, downloaded, total)
        }
    }

    private fun android.database.Cursor.getIntOrNull(columnName: String): Int? {
        val index = getColumnIndex(columnName)
        return if (index >= 0) getInt(index) else null
    }

    private fun android.database.Cursor.getLongOrNull(columnName: String): Long? {
        val index = getColumnIndex(columnName)
        return if (index >= 0) getLong(index) else null
    }

    private fun progressMessage(snapshot: DownloadSnapshot): String {
        val total = snapshot.totalBytes
        val downloaded = snapshot.downloadedBytes
        return if (total > 0 && downloaded >= 0) {
            val pct = ((downloaded * 100) / total).coerceIn(0, 100)
            "Descargando actualización… $pct%"
        } else {
            "Descargando actualización…"
        }
    }

    private fun pausedReason(reason: Int): String = when (reason) {
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Esperando conexión para descargar…"
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Esperando Wi-Fi para descargar…"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Descarga pausada, reintentando…"
        DownloadManager.PAUSED_UNKNOWN -> "Descarga pausada…"
        else -> "Descarga pausada…"
    }

    private fun failedReason(reason: Int): String = when (reason) {
        DownloadManager.ERROR_CANNOT_RESUME -> "No se pudo reanudar la descarga. Vuelve a intentarlo."
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "No se encontró el almacenamiento para guardar la actualización."
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "El APK ya existe. Vuelve a intentarlo."
        DownloadManager.ERROR_FILE_ERROR -> "Error guardando el APK descargado."
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "Error de red durante la descarga."
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "No hay espacio suficiente para descargar la actualización."
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "La descarga tiene demasiadas redirecciones."
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "GitHub devolvió un error al descargar el APK."
        DownloadManager.ERROR_UNKNOWN -> "Descarga fallida por un error desconocido."
        else -> "Descarga fallida (código $reason)."
    }

    private fun launchInstall(apkFile: File) {
        // API 26+ requires the user to grant REQUEST_INSTALL_PACKAGES
        // per-app. If it was previously denied, startActivity with
        // ACTION_VIEW still launches the installer UI but it
        // silently drops the APK when the user accepts. Catching
        // it here gives us a clear Failed event instead of the
        // UI hanging at "Downloaded" forever.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            Timber.w("UpdateInstaller: REQUEST_INSTALL_PACKAGES not granted")
            events.trySend(
                InstallEvent.Failed(
                    "Concede permiso de instalación a Autogram en Ajustes → Apps → Autogram → Instalar apps desconocidas, y vuelve a intentarlo."
                )
            )
            return
        }
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

    private data class DownloadSnapshot(
        val status: Int,
        val reason: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    )

    private companion object {
        const val MONITOR_INTERVAL_MS = 1_000L
        const val STALL_TIMEOUT_MS = 120_000L
        const val MAX_DOWNLOAD_MS = 10 * 60_000L
    }
}
