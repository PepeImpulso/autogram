package com.autogram.app.ui.phone

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.domain.model.CircleFolder
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.repository.CircleFolderRepository
import com.autogram.app.domain.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ManagePinnedViewModel @Inject constructor(
    application: Application,
    private val conversationRepository: ConversationRepository,
    private val folderRepository: CircleFolderRepository
) : AndroidViewModel(application) {

    val pinned: StateFlow<List<Conversation>> =
        conversationRepository.observePinned()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val folders: StateFlow<List<CircleFolder>> =
        folderRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val current = folderRepository.getAll()
            folderRepository.upsert(
                CircleFolder(
                    id = UUID.randomUUID().toString(),
                    name = trimmed,
                    sortOrder = current.size
                )
            )
        }
    }

    fun renameFolder(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val existing = folderRepository.getAll().firstOrNull { it.id == id } ?: return@launch
            folderRepository.upsert(existing.copy(name = trimmed))
        }
    }

    fun deleteFolder(id: String) {
        viewModelScope.launch { folderRepository.delete(id) }
    }

    fun assignFolder(conversationId: String, folderId: String?) {
        viewModelScope.launch {
            conversationRepository.setFolder(conversationId, folderId)
        }
    }

    fun setPrimary(id: String) {
        viewModelScope.launch {
            conversationRepository.setActive(id)
            // Primary is implicitly pinned too so it shows up on main screen.
            conversationRepository.setPinned(id, true)
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            val conv = conversationRepository.getById(id) ?: return@launch
            // Delete the copied image file if any.
            conv.photoLocalPath?.let { runCatching { File(it).delete() } }
            conversationRepository.delete(id)
        }
    }

    fun setPhoto(id: String, source: Uri) {
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val dst = withContext(Dispatchers.IO) {
                    val dir = File(ctx.filesDir, "circle_photos").apply { mkdirs() }
                    val file = File(dir, "$id-${UUID.randomUUID()}.img")
                    // Sample-decode + JPEG re-encode the user's gallery photo
                    // instead of a raw bytestream copy. Modern phones routinely
                    // produce 12-24 MP JPEGs (4-10 MB on disk, 50-100 MB once
                    // decoded) — copying verbatim bloats circle_photos, inflates
                    // export-backup size by ~33 % after base64, and forces every
                    // avatar render to sample-decode at use-time. The avatar
                    // never renders above ~800 px (xxxhdpi primary circle), so
                    // 1600 px is plenty of headroom for ZoomablePhotoDialog's
                    // 6× pinch. Quality 85 JPEG is visually indistinguishable
                    // and keeps the stored file comfortably under ~500 KB.
                    val bitmap = ctx.contentResolver.openInputStream(source)?.use { input ->
                        decodeSampledStream(input, TARGET_LONG_SIDE_PX)
                    } ?: run {
                        runCatching { file.delete() }
                        error("Unsupported or oversized image")
                    }
                    file.outputStream().use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    runCatching { bitmap.recycle() }
                    file
                }
                // Remove any previous image file for this circle.
                val previous = conversationRepository.getById(id)?.photoLocalPath
                if (previous != null && previous != dst.absolutePath) {
                    runCatching { File(previous).delete() }
                }
                conversationRepository.setPhotoPath(id, dst.absolutePath)
            } catch (e: Exception) {
                Timber.e(e, "setPhoto failed")
            }
        }
    }

    /**
     * Two-pass sampled decode from a SAF InputStream. Content URIs aren't
     * seekable so we buffer the whole thing into memory once and run both
     * passes against the byte array. Bounded read prevents OOM on a
     * multi-GB file the SAF picker might hand us (video, archive, etc.).
     *
     * NOTE: `InputStream.readNBytes(int)` was added in API 33; minSdk = 29
     * so we can't use it. Manual copy-with-cap via ByteArrayOutputStream.
     */
    private fun decodeSampledStream(input: java.io.InputStream, targetLongPx: Int): android.graphics.Bitmap? {
        val bytes = readBoundedBytes(input, MAX_SOURCE_PHOTO_BYTES) ?: return null
        if (bytes.isEmpty()) return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val srcLong = kotlin.math.max(bounds.outWidth, bounds.outHeight)
        if (srcLong <= 0) return null
        var sample = 1
        while (srcLong / sample > targetLongPx * 2) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /**
     * Copy the entire input stream into a byte array, but stop and return
     * null if we exceed [maxBytes] — so a user picking a multi-GB file via
     * the SAF picker doesn't OOM the process. Uses plain byte-copy
     * primitives available since Android 1.0 (no readNBytes dependency).
     */
    private fun readBoundedBytes(input: java.io.InputStream, maxBytes: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val r = input.read(buf)
            if (r == -1) break
            total += r
            if (total > maxBytes) return null
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    companion object {
        private const val TARGET_LONG_SIDE_PX: Int = 1600
        // Hard cap on how much we'll read from the SAF picker up front.
        // Larger than any realistic phone photo; protects against picking a
        // multi-GB video or archive by accident.
        private const val MAX_SOURCE_PHOTO_BYTES: Long = 40L * 1024L * 1024L
    }

    fun clearPhoto(id: String) {
        viewModelScope.launch {
            val conv = conversationRepository.getById(id) ?: return@launch
            conv.photoLocalPath?.let { runCatching { File(it).delete() } }
            conversationRepository.setPhotoPath(id, null)
        }
    }

    /**
     * Move the row with `id` up or down within the secondary-circle list.
     * Primary stays at position 0 and is not reorderable (its sortOrder is
     * ignored by the main screen's ordering rule).
     */
    fun moveUp(id: String) = move(id, -1)
    fun moveDown(id: String) = move(id, 1)

    private fun move(id: String, delta: Int) {
        viewModelScope.launch {
            val all = conversationRepository.getPinned()
                .filter { !it.isActive }  // only shuffle non-primary rows
                .sortedBy { it.sortOrder }
            val idx = all.indexOfFirst { it.id == id }
            if (idx < 0) return@launch
            val newIdx = (idx + delta).coerceIn(0, all.lastIndex)
            if (newIdx == idx) return@launch
            val swapped = all.toMutableList().also {
                val tmp = it[idx]; it[idx] = it[newIdx]; it[newIdx] = tmp
            }
            swapped.forEachIndexed { i, conv ->
                conversationRepository.setSortOrder(conv.id, i * 10)
            }
        }
    }
}
