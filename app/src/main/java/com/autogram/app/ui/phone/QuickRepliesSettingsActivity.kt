package com.autogram.app.ui.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.autogram.app.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.domain.model.QuickReply
import com.autogram.app.domain.model.QuickReplyKind
import com.autogram.app.domain.repository.QuickReplyRepository
import com.autogram.app.infrastructure.audio.AudioRecorder
import com.autogram.app.ui.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class QuickRepliesSettingsActivity : BaseLocaleActivity() {

    private val viewModel: QuickRepliesSettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutogramTheme {
                QuickRepliesScreen(viewModel = viewModel, onBack = { finish() })
            }
        }
    }
}

sealed class VoiceRecordState {
    data object Idle : VoiceRecordState()
    data class Recording(val startedAt: Long) : VoiceRecordState()
    /**
     * [isEphemeral] = true when [file] points at a temporary take in
     * cacheDir that should be deleted on reset. False when we're editing
     * an existing persistent shortcut and the file lives in
     * filesDir/voice_shortcuts — those must survive dialog dismissal.
     */
    data class Recorded(val file: File, val durationMs: Long, val isEphemeral: Boolean) : VoiceRecordState()
    data class Playing(val file: File, val durationMs: Long, val isEphemeral: Boolean) : VoiceRecordState()
}

@HiltViewModel
class QuickRepliesSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: QuickReplyRepository,
    private val recorder: AudioRecorder
) : ViewModel() {

    // Editor must see ALL shortcuts including disabled ones, so the
    // user can toggle visibility without losing the entry. The home
    // screen's own observeAll() still filters to enabled-only.
    val replies: StateFlow<List<QuickReply>> =
        repo.observeAllIncludingDisabled().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Standalone MediaPlayer for the in-editor preview. Bypasses the
    // app's AudioPlayer abstraction on purpose: AudioPlayer prefers BT
    // routing + requests audio focus for car playback, which meant the
    // preview was routed to the car speakers (or silenced when focus
    // was denied) while the user was staring at the phone. A plain
    // MediaPlayer on the default output track is what the user actually
    // expects here.
    private var previewMediaPlayer: android.media.MediaPlayer? = null

    private val _voiceState = MutableStateFlow<VoiceRecordState>(VoiceRecordState.Idle)
    val voiceState: StateFlow<VoiceRecordState> = _voiceState.asStateFlow()

    fun saveText(existing: QuickReply?, label: String, text: String) {
        if (label.isBlank() || text.isBlank()) return
        viewModelScope.launch {
            val all = repo.getAll()
            val position = existing?.position ?: ((all.maxOfOrNull { it.position } ?: 0) + 1)
            repo.upsert(
                QuickReply(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    label = label.trim(),
                    kind = QuickReplyKind.TEXT,
                    textContent = text.trim(),
                    audioFilePath = null,
                    audioDurationMs = 0L,
                    position = position,
                    isEnabled = existing?.isEnabled ?: true
                )
            )
        }
    }

    fun saveVoice(existing: QuickReply?, label: String) {
        if (label.isBlank()) return
        val state = _voiceState.value
        // Three cases: (1) a brand-new recording is pending (Recorded or
        // Playing), (2) editing an existing voice shortcut with no new take
        // — just relabel — (3) no recording anywhere: can't save.
        viewModelScope.launch {
            val id = existing?.id ?: UUID.randomUUID().toString()
            val audioPath: String
            val durationMs: Long
            when (state) {
                is VoiceRecordState.Recorded, is VoiceRecordState.Playing -> {
                    val (src, dur, ephemeral) = when (state) {
                        is VoiceRecordState.Recorded -> Triple(state.file, state.durationMs, state.isEphemeral)
                        is VoiceRecordState.Playing -> Triple(state.file, state.durationMs, state.isEphemeral)
                        else -> return@launch
                    }
                    // If the user opened the editor to edit an existing
                    // shortcut and never re-recorded, the file is already
                    // at filesDir/voice_shortcuts/$id.ogg and we're just
                    // saving a (possibly new) label. Nothing to copy.
                    if (!ephemeral) {
                        audioPath = src.absolutePath
                        durationMs = dur
                    } else {
                        // Atomic replace: copy to a staging file next to dst,
                        // rename over dst, THEN delete src. If the copy throws,
                        // the old shortcut survives intact and we bail out.
                        val dir = File(context.filesDir, "voice_shortcuts").apply { mkdirs() }
                        val dst = File(dir, "$id.ogg")
                        val staging = File(dir, "$id.ogg.new")
                        runCatching { if (staging.exists()) staging.delete() }
                        val copyResult = runCatching { src.copyTo(staging, overwrite = true) }
                        if (copyResult.isFailure) {
                            Timber.w(copyResult.exceptionOrNull(), "voice shortcut save: copy failed; keeping previous file")
                            runCatching { staging.delete() }
                            return@launch
                        }
                        // Atomic replace: Files.move + REPLACE_EXISTING
                        // maps to POSIX rename(2) on Android, which is
                        // atomic at the filesystem level. Previous
                        // code did `dst.delete() + staging.renameTo(dst)`
                        // which had a window between delete and rename
                        // where a process kill would leave the user
                        // with the old shortcut gone and no replacement.
                        val moved = runCatching {
                            java.nio.file.Files.move(
                                staging.toPath(),
                                dst.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE
                            )
                        }
                        if (moved.isFailure) {
                            Timber.w(moved.exceptionOrNull(), "voice shortcut save: atomic move failed; keeping previous file")
                            runCatching { staging.delete() }
                            return@launch
                        }
                        runCatching { src.delete() }
                        audioPath = dst.absolutePath
                        durationMs = dur
                    }
                }
                else -> {
                    // Idle + editing existing: keep the old audio, relabel only.
                    val p = existing?.audioFilePath
                    if (p.isNullOrBlank()) return@launch
                    audioPath = p
                    durationMs = existing.audioDurationMs
                }
            }
            val all = repo.getAll()
            val position = existing?.position ?: ((all.maxOfOrNull { it.position } ?: 0) + 1)
            repo.upsert(
                QuickReply(
                    id = id,
                    label = label.trim(),
                    kind = QuickReplyKind.VOICE,
                    textContent = "",
                    audioFilePath = audioPath,
                    audioDurationMs = durationMs,
                    position = position,
                    isEnabled = existing?.isEnabled ?: true
                )
            )
            _voiceState.value = VoiceRecordState.Idle
        }
    }

    fun delete(reply: QuickReply) {
        viewModelScope.launch {
            // Best-effort delete of the voice file when removing a voice
            // shortcut. If the row vanished but the file survived it's dead
            // weight; the retention worker will clean it up eventually but
            // this gives immediate feedback on storage use.
            if (reply.kind == QuickReplyKind.VOICE) {
                reply.audioFilePath?.let { runCatching { File(it).delete() } }
            }
            repo.delete(reply.id)
        }
    }

    /**
     * Show/hide a shortcut on the home screen without deleting it.
     * [QuickReplyDao.observeAll] already filters out `isEnabled = 0`
     * rows, so flipping this flag is all the main screen needs to
     * stop showing it. The row stays in the editor list so the user
     * can re-enable it later or edit the label without losing the
     * audio file.
     */
    fun setEnabled(reply: QuickReply, enabled: Boolean) {
        if (reply.isEnabled == enabled) return
        viewModelScope.launch {
            repo.upsert(reply.copy(isEnabled = enabled))
        }
    }

    fun startRecording() {
        if (_voiceState.value is VoiceRecordState.Recording) return
        // MediaRecorder.prepare()+start() can block 50-300ms on cold mic on
        // some OEMs; off-load to IO so the Compose UI doesn't jank while
        // the user's finger is still on the Record button.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dir = File(context.cacheDir, "voice_shortcut_tmp").apply { mkdirs() }
            val tmp = File(dir, "shortcut-${System.currentTimeMillis()}.ogg")
            val result = recorder.start(tmp, maxDurationMs = 60_000)
            if (result.isSuccess) {
                _voiceState.value = VoiceRecordState.Recording(System.currentTimeMillis())
            } else {
                Timber.w("Voice shortcut record start failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun stopRecording() {
        val state = _voiceState.value
        if (state !is VoiceRecordState.Recording) return
        // MediaRecorder.stop() can briefly block while flushing buffers.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val durationMs = System.currentTimeMillis() - state.startedAt
            val file = recorder.stop()
            if (file != null && file.exists() && file.length() > 0L) {
                _voiceState.value = VoiceRecordState.Recorded(file, durationMs, isEphemeral = true)
            } else {
                _voiceState.value = VoiceRecordState.Idle
            }
        }
    }

    /**
     * Pre-load an existing voice shortcut into the editor state so the
     * user sees the saved recording (with Preview / Redo controls)
     * instead of the "Record" button when re-entering the editor.
     * The file lives in filesDir/voice_shortcuts so it's non-ephemeral
     * and [resetVoiceState] must not delete it.
     */
    fun loadExisting(audioFilePath: String, durationMs: Long) {
        val f = File(audioFilePath)
        if (!f.exists() || f.length() == 0L) {
            Timber.w("loadExisting: file missing or empty at $audioFilePath")
            return
        }
        _voiceState.value = VoiceRecordState.Recorded(f, durationMs, isEphemeral = false)
    }

    fun previewPlay() {
        val state = _voiceState.value
        val (file, duration, ephemeral) = when (state) {
            is VoiceRecordState.Recorded -> Triple(state.file, state.durationMs, state.isEphemeral)
            is VoiceRecordState.Playing -> return  // already playing
            else -> return
        }
        // Release any leftover player from a previous preview.
        runCatching { previewMediaPlayer?.release() }
        previewMediaPlayer = null
        _voiceState.value = VoiceRecordState.Playing(file, duration, ephemeral)
        try {
            val mp = android.media.MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    _voiceState.value = VoiceRecordState.Recorded(file, duration, ephemeral)
                    runCatching { release() }
                    previewMediaPlayer = null
                }
                setOnErrorListener { _, what, extra ->
                    Timber.w("Preview MediaPlayer error what=$what extra=$extra file=${file.absolutePath}")
                    _voiceState.value = VoiceRecordState.Recorded(file, duration, ephemeral)
                    runCatching { release() }
                    previewMediaPlayer = null
                    true
                }
                prepare()
                start()
            }
            previewMediaPlayer = mp
        } catch (e: Exception) {
            Timber.w(e, "Preview play failed for ${file.absolutePath}")
            _voiceState.value = VoiceRecordState.Recorded(file, duration, ephemeral)
            previewMediaPlayer = null
        }
    }

    fun previewStop() {
        val state = _voiceState.value
        if (state is VoiceRecordState.Playing) {
            runCatching { previewMediaPlayer?.stop() }
            runCatching { previewMediaPlayer?.release() }
            previewMediaPlayer = null
            _voiceState.value = VoiceRecordState.Recorded(state.file, state.durationMs, state.isEphemeral)
        }
    }

    /**
     * Reset in-memory state without touching persistent files.
     *
     * Temp recordings in cacheDir ARE deleted here (they'd otherwise
     * leak). Existing voice shortcuts loaded via [loadExisting] are
     * NOT — they're the user's saved data and must survive dialog
     * dismissal, rotation, and navigation away.
     */
    fun resetVoiceState() {
        runCatching { previewMediaPlayer?.stop() }
        runCatching { previewMediaPlayer?.release() }
        previewMediaPlayer = null
        val state = _voiceState.value
        if (state is VoiceRecordState.Recording) {
            runCatching { recorder.stop()?.delete() }
        }
        if (state is VoiceRecordState.Recorded && state.isEphemeral) {
            runCatching { state.file.delete() }
        }
        if (state is VoiceRecordState.Playing && state.isEphemeral) {
            runCatching { state.file.delete() }
        }
        _voiceState.value = VoiceRecordState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        resetVoiceState()
    }
}

private sealed class EditorTarget {
    data object None : EditorTarget()
    data class Text(val existing: QuickReply?) : EditorTarget()
    data class Voice(val existing: QuickReply?) : EditorTarget()
    data object KindPicker : EditorTarget()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickRepliesScreen(
    viewModel: QuickRepliesSettingsViewModel,
    onBack: () -> Unit
) {
    val replies by viewModel.replies.collectAsState()
    var target by remember { mutableStateOf<EditorTarget>(EditorTarget.None) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.quick_replies_title),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { target = EditorTarget.KindPicker },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.quick_replies_new)) }
            )
        }
    ) { padding ->
        if (replies.isEmpty()) {
            QuickRepliesEmptyState(modifier = Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(replies, key = { it.id }) { r ->
                QuickReplyCard(
                    reply = r,
                    onTap = {
                        target = if (r.kind == QuickReplyKind.VOICE) EditorTarget.Voice(r)
                                 else EditorTarget.Text(r)
                    },
                    onDelete = { viewModel.delete(r) },
                    onEnabledChange = { viewModel.setEnabled(r, it) }
                )
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    when (val t = target) {
        is EditorTarget.None -> Unit
        is EditorTarget.KindPicker -> KindPickerSheet(
            onPickText = { target = EditorTarget.Text(null) },
            onPickVoice = { target = EditorTarget.Voice(null) },
            onDismiss = { target = EditorTarget.None }
        )
        is EditorTarget.Text -> QuickReplyTextEditor(
            initial = t.existing,
            onSave = { label, text ->
                viewModel.saveText(t.existing, label, text)
                target = EditorTarget.None
            },
            onDismiss = { target = EditorTarget.None }
        )
        is EditorTarget.Voice -> QuickReplyVoiceEditor(
            viewModel = viewModel,
            initial = t.existing,
            onSave = { label ->
                viewModel.saveVoice(t.existing, label)
                target = EditorTarget.None
            },
            onDismiss = {
                viewModel.resetVoiceState()
                target = EditorTarget.None
            }
        )
    }
}

@Composable
private fun QuickRepliesEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(84.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.quick_replies_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun QuickReplyCard(
    reply: QuickReply,
    onTap: () -> Unit,
    onDelete: () -> Unit,
    onEnabledChange: (Boolean) -> Unit
) {
    val isVoice = reply.kind == QuickReplyKind.VOICE
    ElevatedCard(
        onClick = onTap,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            // Greyed container when disabled so the user sees at a
            // glance which shortcuts are hidden from the home screen
            // without having to read the switch state.
            containerColor = if (reply.isEnabled) MaterialTheme.colorScheme.surface
                             else MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isVoice) MaterialTheme.colorScheme.tertiaryContainer
                        else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isVoice) Icons.Filled.Mic else Icons.Filled.Bolt,
                        contentDescription = null,
                        tint = if (isVoice) MaterialTheme.colorScheme.onTertiaryContainer
                               else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    reply.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (reply.isEnabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (isVoice) formatDuration(reply.audioDurationMs) else reply.textContent,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            // Show/hide on home switch. Pre-empts the card's own click
            // handler so toggling doesn't also open the editor.
            Switch(
                checked = reply.isEnabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.padding(end = 4.dp)
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).toInt().coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindPickerSheet(
    onPickText: () -> Unit,
    onPickVoice: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.quick_replies_pick_kind),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
            KindOption(
                icon = Icons.Filled.TextFields,
                title = stringResource(R.string.quick_replies_kind_text),
                subtitle = stringResource(R.string.quick_replies_kind_text_sub),
                onClick = onPickText
            )
            KindOption(
                icon = Icons.Filled.Mic,
                title = stringResource(R.string.quick_replies_kind_voice),
                subtitle = stringResource(R.string.quick_replies_kind_voice_sub),
                onClick = onPickVoice
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun KindOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun QuickReplyTextEditor(
    initial: QuickReply?,
    onSave: (label: String, text: String) -> Unit,
    onDismiss: () -> Unit
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var text by remember { mutableStateOf(initial?.textContent ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) stringResource(R.string.quick_replies_dialog_new_title) else stringResource(R.string.quick_replies_dialog_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.quick_replies_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.quick_replies_text)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(label, text) },
                enabled = label.isNotBlank() && text.isNotBlank()
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
private fun QuickReplyVoiceEditor(
    viewModel: QuickRepliesSettingsViewModel,
    initial: QuickReply?,
    onSave: (label: String) -> Unit,
    onDismiss: () -> Unit
) {
    val voiceState by viewModel.voiceState.collectAsState()
    var label by remember { mutableStateOf(initial?.label ?: "") }
    val ctx = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startRecording()
    }

    // Pre-load the existing recording into VM state so the editor shows
    // Preview / Redo controls instead of the Record button — users
    // re-entering to edit the label expect to see what's already saved.
    // Keyed on initial.id so switching between edit targets re-loads.
    LaunchedEffect(initial?.id) {
        val path = initial?.audioFilePath
        if (initial?.kind == QuickReplyKind.VOICE && !path.isNullOrBlank()) {
            viewModel.loadExisting(path, initial.audioDurationMs)
        }
    }

    // Guarantees the recorder/player is released when the dialog leaves
    // composition for any reason (save, cancel, back-press, rotation). The
    // VM's resetVoiceState is idempotent so calling it here and in the
    // explicit dismiss paths is harmless. Importantly it only deletes
    // ephemeral (cacheDir) files; persistent voice shortcuts survive.
    DisposableEffect(Unit) {
        onDispose { viewModel.resetVoiceState() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial == null) stringResource(R.string.quick_replies_voice_new_title)
                else stringResource(R.string.quick_replies_voice_edit_title)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.quick_replies_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                VoiceRecorderControls(
                    state = voiceState,
                    onStartRecord = {
                        val granted = ContextCompat.checkSelfPermission(
                            ctx, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) viewModel.startRecording()
                        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onStopRecord = { viewModel.stopRecording() },
                    onPlay = { viewModel.previewPlay() },
                    onStopPlay = { viewModel.previewStop() },
                    onDiscard = { viewModel.resetVoiceState() }
                )
            }
        },
        confirmButton = {
            val hasNewRecording = voiceState is VoiceRecordState.Recorded ||
                                  voiceState is VoiceRecordState.Playing
            val hasExistingAudio = initial?.audioFilePath?.isNotBlank() == true
            TextButton(
                onClick = { onSave(label) },
                enabled = label.isNotBlank() && (hasNewRecording || hasExistingAudio)
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
private fun VoiceRecorderControls(
    state: VoiceRecordState,
    onStartRecord: () -> Unit,
    onStopRecord: () -> Unit,
    onPlay: () -> Unit,
    onStopPlay: () -> Unit,
    onDiscard: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val label = when (state) {
                VoiceRecordState.Idle -> stringResource(R.string.quick_replies_voice_hint_idle)
                is VoiceRecordState.Recording -> stringResource(R.string.quick_replies_voice_recording)
                is VoiceRecordState.Recorded -> stringResource(
                    R.string.quick_replies_voice_ready,
                    formatDuration(state.durationMs)
                )
                is VoiceRecordState.Playing -> stringResource(R.string.quick_replies_voice_playing)
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (state) {
                    VoiceRecordState.Idle -> {
                        FilledTonalButton(
                            onClick = onStartRecord,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Mic, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.quick_replies_voice_start))
                        }
                    }
                    is VoiceRecordState.Recording -> {
                        FilledTonalButton(
                            onClick = onStopRecord,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Icon(Icons.Filled.Stop, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.quick_replies_voice_stop))
                        }
                    }
                    is VoiceRecordState.Recorded -> {
                        FilledTonalButton(
                            onClick = onPlay,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.quick_replies_voice_preview))
                        }
                        OutlinedButton(onClick = onDiscard) {
                            Text(stringResource(R.string.quick_replies_voice_redo))
                        }
                    }
                    is VoiceRecordState.Playing -> {
                        FilledTonalButton(
                            onClick = onStopPlay,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Stop, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.quick_replies_voice_stop_preview))
                        }
                        OutlinedButton(onClick = onDiscard) {
                            Text(stringResource(R.string.quick_replies_voice_redo))
                        }
                    }
                }
            }
        }
    }
}
