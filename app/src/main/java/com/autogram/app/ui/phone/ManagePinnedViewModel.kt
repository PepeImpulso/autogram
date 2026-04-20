package com.autogram.app.ui.phone

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.domain.model.Conversation
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
    private val conversationRepository: ConversationRepository
) : AndroidViewModel(application) {

    val pinned: StateFlow<List<Conversation>> =
        conversationRepository.observePinned()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
                    ctx.contentResolver.openInputStream(source)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
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

    fun clearPhoto(id: String) {
        viewModelScope.launch {
            val conv = conversationRepository.getById(id) ?: return@launch
            conv.photoLocalPath?.let { runCatching { File(it).delete() } }
            conversationRepository.setPhotoPath(id, null)
        }
    }
}
