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
import androidx.compose.material.icons.filled.DragHandle
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
import com.autogram.app.domain.model.AppSettings
import com.autogram.app.domain.model.QuickReply
import com.autogram.app.domain.model.QuickReplyKind
import com.autogram.app.domain.repository.QuickReplyRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.infrastructure.audio.AudioRecorder
import com.autogram.app.ui.theme.AutogramTheme
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
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
                DebugScreenshotOverlay {
                    QuickRepliesScreen(viewModel = viewModel, onBack = { finish() })
                }
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
    private val conversationRepository: com.autogram.app.domain.repository.ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val recorder: AudioRecorder
) : ViewModel() {

    // Editor must see ALL shortcuts including disabled ones, so the
    // user can toggle visibility without losing the entry. The home
    // screen's own observeAll() still filters to enabled-only.
    val replies: StateFlow<List<QuickReply>> =
        repo.observeAllIncludingDisabled().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Pinned circles fed into the "Send to" picker in the shortcut editor.
    // Empty (= no pinned circles yet) collapses the picker to "follow primary"
    // since there's nothing else to bind to.
    val pinned: StateFlow<List<com.autogram.app.domain.model.Conversation>> =
        conversationRepository.observePinned()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val settings: StateFlow<AppSettings> =
        settingsRepository.observe()
            .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

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

    fun saveText(existing: QuickReply?, label: String, text: String, targetConversationId: String?) {
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
                    isEnabled = existing?.isEnabled ?: true,
                    targetConversationId = targetConversationId
                )
            )
        }
    }

    fun saveVoice(existing: QuickReply?, label: String, targetConversationId: String?) {
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
                    isEnabled = existing?.isEnabled ?: true,
                    targetConversationId = targetConversationId
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

    fun setShowQuickReplyFilters(show: Boolean) {
        if (settings.value.showQuickReplyFilters == show) return
        viewModelScope.launch {
            val current = settingsRepository.get()
            if (current.showQuickReplyFilters != show) {
                settingsRepository.update(current.copy(showQuickReplyFilters = show))
            }
        }
    }

    fun setShowQuickReplyShortcuts(show: Boolean) {
        if (settings.value.showQuickReplyShortcuts == show) return
        viewModelScope.launch {
            val current = settingsRepository.get()
            if (current.showQuickReplyShortcuts != show) {
                settingsRepository.update(current.copy(showQuickReplyShortcuts = show))
            }
        }
    }

    /**
     * Persist the drag-reorder. [orderedIds] is the full shortcut list
     * in the order the user dragged them into. Repository writes
     * `position = index` for each id and the home + editor feeds
     * (both ORDER BY position ASC) reflect the new order on the next
     * Flow emission. Called from the Shortcuts editor on drag-drop.
     */
    fun reorder(orderedIds: List<String>) {
        viewModelScope.launch {
            repo.reorder(orderedIds)
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
                    Timber.w("Preview MediaPlayer error what=$what extra=$extra file=${file.name}")
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
            Timber.w(e, "Preview play failed for ${file.name}")
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

/**
 * Converts the LazyColumn indices emitted by the reorderable library into
 * shortcut-list indices. The visibility card occupies index 0, so using the
 * raw indices against [items] can address index N in a list whose last valid
 * index is N - 1. Invalid transient targets (for example the footer spacer)
 * are clamped or ignored instead of crashing the activity mid-drag.
 */
internal fun <T> reorderShortcutItems(
    items: List<T>,
    fromLazyIndex: Int,
    toLazyIndex: Int
): List<T> {
    if (items.isEmpty()) return items
    val from = fromLazyIndex - SHORTCUT_LIST_HEADER_COUNT
    if (from !in items.indices) return items
    val to = (toLazyIndex - SHORTCUT_LIST_HEADER_COUNT).coerceIn(items.indices)
    if (from == to) return items
    return items.toMutableList().apply {
        add(to, removeAt(from))
    }
}

private const val SHORTCUT_LIST_HEADER_COUNT = 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickRepliesScreen(
    viewModel: QuickRepliesSettingsViewModel,
    onBack: () -> Unit
) {
    val replies by viewModel.replies.collectAsState()
    val pinned by viewModel.pinned.collectAsState()
    val settings by viewModel.settings.collectAsState()
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
        // Drag-reorder: keep an optional draft order that wins over the
        // upstream Flow while a drag is in progress, so the dragged item
        // glides smoothly without flicker. Cleared once the persisted
        // order matches the draft (LaunchedEffect below).
        var draftOrder by remember { mutableStateOf<List<QuickReply>?>(null) }
        val displayList = draftOrder ?: replies

        LaunchedEffect(replies, draftOrder) {
            val draftIds = draftOrder?.map { it.id }
            if (draftIds != null && draftIds == replies.map { it.id }) {
                draftOrder = null
            }
        }

        val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
        val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
            val current = draftOrder ?: replies
            draftOrder = reorderShortcutItems(
                items = current,
                fromLazyIndex = from.index,
                toLazyIndex = to.index
            )
        }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                HomeShortcutVisibilityCard(
                    showFilters = settings.showQuickReplyFilters,
                    showShortcuts = settings.showQuickReplyShortcuts,
                    onShowFiltersChange = viewModel::setShowQuickReplyFilters,
                    onShowShortcutsChange = viewModel::setShowQuickReplyShortcuts
                )
            }
            if (displayList.isEmpty()) {
                item {
                    QuickRepliesEmptyState(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 260.dp)
                    )
                }
            } else {
                items(displayList, key = { it.id }) { r ->
                    ReorderableItem(reorderableState, key = r.id) { isDragging ->
                        QuickReplyCard(
                            reply = r,
                            isDragging = isDragging,
                            dragHandleModifier = Modifier.draggableHandle(
                                onDragStopped = {
                                    draftOrder?.let { d -> viewModel.reorder(d.map { it.id }) }
                                }
                            ),
                            onTap = {
                                target = if (r.kind == QuickReplyKind.VOICE) EditorTarget.Voice(r)
                                         else EditorTarget.Text(r)
                            },
                            onDelete = { viewModel.delete(r) },
                            onEnabledChange = { viewModel.setEnabled(r, it) }
                        )
                    }
                }
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
            pinned = pinned,
            onSave = { label, text, targetId ->
                viewModel.saveText(t.existing, label, text, targetId)
                target = EditorTarget.None
            },
            onDismiss = { target = EditorTarget.None }
        )
        is EditorTarget.Voice -> QuickReplyVoiceEditor(
            viewModel = viewModel,
            initial = t.existing,
            pinned = pinned,
            onSave = { label, targetId ->
                viewModel.saveVoice(t.existing, label, targetId)
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
private fun HomeShortcutVisibilityCard(
    showFilters: Boolean,
    showShortcuts: Boolean,
    onShowFiltersChange: (Boolean) -> Unit,
    onShowShortcutsChange: (Boolean) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.quick_replies_home_visibility_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.quick_replies_home_visibility_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            VisibilitySwitchRow(
                title = stringResource(R.string.quick_replies_show_filters),
                subtitle = stringResource(R.string.quick_replies_show_filters_desc),
                checked = showFilters,
                onCheckedChange = onShowFiltersChange
            )
            VisibilitySwitchRow(
                title = stringResource(R.string.quick_replies_show_shortcuts),
                subtitle = stringResource(R.string.quick_replies_show_shortcuts_desc),
                checked = showShortcuts,
                onCheckedChange = onShowShortcutsChange
            )
        }
    }
}

@Composable
private fun VisibilitySwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
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
    isDragging: Boolean,
    dragHandleModifier: Modifier,
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
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = if (isDragging) 8.dp else 1.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 14.dp)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Drag handle on the leading edge so the card's main click
            // target (tap-to-edit) and the drag affordance don't fight
            // for the same gesture surface. .draggableHandle() comes
            // from sh.calvin.reorderable; long-press isn't required —
            // a press-and-drag from the handle starts the reorder.
            Box(
                modifier = dragHandleModifier
                    .size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = stringResource(R.string.quick_replies_reorder_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(6.dp))
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

/**
 * Inline picker that decides which contact a shortcut sends to. The default
 * (selectedId = null) follows the on-screen primary circle — that's the
 * legacy behaviour, kept so existing shortcuts don't change semantics.
 * Selecting a specific pinned circle pins the shortcut there: tapping it on
 * Home or Android Auto always sends to that contact regardless of which
 * circle happens to be primary.
 *
 * Collapses to a single read-only line when there are no pinned circles to
 * pick from — picker is irrelevant in that state and showing an empty
 * dropdown would confuse the user.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendToPicker(
    pinned: List<com.autogram.app.domain.model.Conversation>,
    selectedId: String?,
    onSelect: (String?) -> Unit
) {
    if (pinned.isEmpty()) {
        Text(
            stringResource(R.string.quick_replies_send_to_no_circles),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val selected = pinned.firstOrNull { it.id == selectedId }
    val display = selected?.let {
        it.topicName?.takeIf { t -> t.isNotBlank() } ?: it.targetTitle
            ?: stringResource(R.string.main_default_primary_name)
    } ?: stringResource(R.string.quick_replies_send_to_primary)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = display,
            onValueChange = { },
            readOnly = true,
            label = { Text(stringResource(R.string.quick_replies_send_to_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.quick_replies_send_to_primary)) },
                onClick = { onSelect(null); expanded = false }
            )
            pinned.forEach { conv ->
                val name = conv.topicName?.takeIf { it.isNotBlank() } ?: conv.targetTitle
                    ?: stringResource(R.string.main_default_primary_name)
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { onSelect(conv.id); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun QuickReplyTextEditor(
    initial: QuickReply?,
    pinned: List<com.autogram.app.domain.model.Conversation>,
    onSave: (label: String, text: String, targetConversationId: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var text by remember { mutableStateOf(initial?.textContent ?: "") }
    var targetId by remember { mutableStateOf(initial?.targetConversationId) }

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
                SendToPicker(
                    pinned = pinned,
                    selectedId = targetId,
                    onSelect = { targetId = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(label, text, targetId) },
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
    pinned: List<com.autogram.app.domain.model.Conversation>,
    onSave: (label: String, targetConversationId: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val voiceState by viewModel.voiceState.collectAsState()
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var targetId by remember { mutableStateOf(initial?.targetConversationId) }
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
                SendToPicker(
                    pinned = pinned,
                    selectedId = targetId,
                    onSelect = { targetId = it }
                )
            }
        },
        confirmButton = {
            val hasNewRecording = voiceState is VoiceRecordState.Recorded ||
                                  voiceState is VoiceRecordState.Playing
            val hasExistingAudio = initial?.audioFilePath?.isNotBlank() == true
            TextButton(
                onClick = { onSave(label, targetId) },
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
