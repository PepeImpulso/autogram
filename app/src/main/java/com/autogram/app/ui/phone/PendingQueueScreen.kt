package com.autogram.app.ui.phone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.R
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.usecase.PlayReplyUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PendingQueueViewModel @Inject constructor(
    messageRepository: MessageRepository,
    conversationRepository: ConversationRepository,
    private val playReplyUseCase: PlayReplyUseCase
) : ViewModel() {

    /** Selected contact filter — `null` means "all contacts". */
    private val _contactFilter = MutableStateFlow<String?>(null)
    val contactFilter: StateFlow<String?> = _contactFilter.asStateFlow()

    val pinned: StateFlow<List<Conversation>> =
        conversationRepository.observePinned()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val conversationsById: StateFlow<Map<String, Conversation>> =
        conversationRepository.observePinned()
            .map { list -> list.associateBy { it.id } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Unfiltered queue — drives the tab-bar badge so it always shows total pending. */
    val totalQueue: StateFlow<List<Message>> =
        messageRepository.observePendingPlaybackQueue()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * v1.22.0 — pending queue filtered by contact (instead of the old
     * All/Voice/Text type filter, which the user found redundant).
     */
    val queue: StateFlow<List<Message>> = combine(
        totalQueue,
        _contactFilter
    ) { msgs, filterId ->
        if (filterId == null) msgs else msgs.filter { it.conversationId == filterId }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val nowPlaying: StateFlow<Message?> =
        messageRepository.observeNowPlaying()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val playbackStatus: StateFlow<PlayReplyUseCase.PlaybackStatus> = playReplyUseCase.playbackStatus

    fun conversationFor(message: Message): Conversation? = conversationsById.value[message.conversationId]

    fun setContactFilter(id: String?) { _contactFilter.value = id }

    fun playAll() = viewModelScope.launch { playReplyUseCase.playAllPending() }

    fun playFromHere(message: Message) = viewModelScope.launch {
        playReplyUseCase.playFromHere(message)
    }

    fun pause() = playReplyUseCase.pause()
    fun resume() = playReplyUseCase.resume()

    /**
     * v1.24.4 — what the NowPlayingPanel's Play button calls when tapped.
     * Decides between resume (we have a live MediaPlayer / TTS engine
     * paused mid-playback) and a fresh play-from-here (the row is in
     * PLAYING/PAUSED state in the DB but no in-memory player exists —
     * usually because the process was killed mid-playback and the row
     * survived in a stale state). Without this branch the panel's Play
     * button is a no-op on the stale-state case, which is exactly the
     * "play does nothing the first time" bug reported in v1.24.3.
     */
    fun playOrResumeNowPlaying() = viewModelScope.launch {
        val msg = nowPlaying.value ?: return@launch
        when (playReplyUseCase.playbackStatus.value) {
            is PlayReplyUseCase.PlaybackStatus.Paused -> playReplyUseCase.resume()
            is PlayReplyUseCase.PlaybackStatus.Playing -> Unit
            else -> playReplyUseCase.playFromHere(msg)
        }
    }

    fun skipNext() = viewModelScope.launch { playReplyUseCase.skipNext() }
    fun skipPrevious() = viewModelScope.launch { playReplyUseCase.skipPrevious() }
    fun restartCurrent() = viewModelScope.launch { playReplyUseCase.restartCurrent() }
    fun markAllAsListened() = viewModelScope.launch { playReplyUseCase.markAllPendingAsListened() }

    val canSkipPrevious: StateFlow<Boolean> = playReplyUseCase.canSkipPrevious

    fun currentPositionMs(): Int? = playReplyUseCase.currentPositionMs()
    fun durationMs(): Int? = playReplyUseCase.durationMs()

    fun seekTo(positionMs: Int) = playReplyUseCase.seekTo(positionMs)
}

/**
 * Pending Queue tab body. Hosted inside [HistoryWithPendingTabsScreen] —
 * owns no Scaffold or top bar. Contact filter chips at the top, the
 * "Play All" affordance, then a list of [MessageRow]s, with a
 * [NowPlayingPanel] pinned at the bottom whenever something is
 * playing or paused.
 */
@Composable
internal fun PendingQueueTabContent(viewModel: PendingQueueViewModel) {
    val queue by viewModel.queue.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val playback by viewModel.playbackStatus.collectAsState()
    val contactFilter by viewModel.contactFilter.collectAsState()
    val pinned by viewModel.pinned.collectAsState()
    val canSkipPrevious by viewModel.canSkipPrevious.collectAsState()
    var confirmMarkAll by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        ContactFilterRow(
            pinned = pinned,
            selectedId = contactFilter,
            onSelect = viewModel::setContactFilter,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )
        if (queue.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MarkAllListenedButton(onClick = { confirmMarkAll = true })
                PlayAllButton(onClick = { viewModel.playAll() })
            }
        }

        if (queue.isEmpty() && nowPlaying == null) {
            EmptyQueue(modifier = Modifier.weight(1f).fillMaxWidth())
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(queue, key = { it.id }) { msg ->
                    val pb = playback
                    val isPlayingThis = pb is PlayReplyUseCase.PlaybackStatus.Playing &&
                        pb.messageId == msg.id
                    val isPausedThis = pb is PlayReplyUseCase.PlaybackStatus.Paused &&
                        pb.messageId == msg.id
                    MessageRow(
                        message = msg,
                        conversation = viewModel.conversationFor(msg),
                        isPlayingThis = isPlayingThis,
                        isPausedThis = isPausedThis,
                        onPlay = { viewModel.playFromHere(msg) },
                        onPause = viewModel::pause,
                        onResume = viewModel::resume,
                        positionMsProvider = { viewModel.currentPositionMs() },
                        durationMsProvider = { viewModel.durationMs() },
                        onSeek = viewModel::seekTo
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }

        NowPlayingPanel(
            message = nowPlaying,
            conversation = nowPlaying?.let { viewModel.conversationFor(it) },
            playback = playback,
            positionMsProvider = { viewModel.currentPositionMs() },
            durationMsProvider = { viewModel.durationMs() },
            onPause = viewModel::pause,
            // v1.24.4 — playOrResumeNowPlaying handles the
            // "stale-state Play tap" case where resume() would be a no-op.
            onResume = { viewModel.playOrResumeNowPlaying() },
            onSkipNext = { viewModel.skipNext() },
            onSkipPrevious = { viewModel.skipPrevious() },
            canSkipPrevious = canSkipPrevious,
            onSeek = viewModel::seekTo
        )
    }

    if (confirmMarkAll) {
        AlertDialog(
            onDismissRequest = { confirmMarkAll = false },
            title = { Text(stringResource(R.string.pending_queue_mark_all_confirm_title)) },
            text = { Text(stringResource(R.string.pending_queue_mark_all_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmMarkAll = false
                    viewModel.markAllAsListened()
                }) {
                    Text(stringResource(R.string.pending_queue_mark_all_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmMarkAll = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun MarkAllListenedButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.height(36.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Filled.DoneAll,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                stringResource(R.string.pending_queue_mark_all_listened),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PlayAllButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.height(36.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.pending_queue_play_all),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun EmptyQueue(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.GraphicEq,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.pending_queue_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NowPlayingPanel(
    message: Message?,
    conversation: Conversation?,
    playback: PlayReplyUseCase.PlaybackStatus,
    positionMsProvider: () -> Int?,
    durationMsProvider: () -> Int?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    canSkipPrevious: Boolean,
    onSeek: (Int) -> Unit
) {
    if (message == null) return
    var expanded by remember { mutableStateOf(true) }

    var positionMs by remember { mutableIntStateOf(positionMsProvider() ?: 0) }
    var durationMs by remember { mutableIntStateOf(durationMsProvider() ?: 0) }
    val isPlaying = playback is PlayReplyUseCase.PlaybackStatus.Playing
    LaunchedEffect(isPlaying, message.id) {
        while (isPlaying) {
            positionMs = positionMsProvider() ?: positionMs
            durationMs = durationMsProvider() ?: durationMs
            delay(250)
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.pending_queue_now_playing),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ConversationAvatarLocal(conversation = conversation, size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = conversation?.let {
                                    it.topicName?.takeIf { t -> t.isNotBlank() } ?: it.targetTitle
                                } ?: stringResource(R.string.main_default_primary_name),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (!message.audioFilePath.isNullOrBlank()) {
                                    stringResource(
                                        R.string.pending_queue_row_voice,
                                        formatDurationMs(message.audioDurationMs ?: 0L)
                                    )
                                } else {
                                    stringResource(R.string.pending_queue_row_text_generic)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    // v1.23.0-rc.6 — tap or horizontal-drag on the waveform
                    // jumps playback to that position. We track the
                    // measured pixel width via onSizeChanged so taps
                    // translate to a 0..1 fraction of the audio that's
                    // independent of the device width / orientation.
                    var waveformWidthPx by remember { mutableIntStateOf(0) }
                    val seekFromX: (Float) -> Unit = { x ->
                        val width = waveformWidthPx
                        val total = if (durationMs > 0) durationMs else (message.audioDurationMs?.toInt() ?: 0)
                        if (width > 0 && total > 0) {
                            val fraction = (x / width.toFloat()).coerceIn(0f, 1f)
                            val target = (fraction * total).toInt()
                            // Update local state immediately so the UI
                            // doesn't briefly show the old position
                            // before AudioPlayer reports the new one.
                            positionMs = target
                            onSeek(target)
                        }
                    }
                    Waveform(
                        messageId = message.id,
                        progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .onSizeChanged { size: IntSize -> waveformWidthPx = size.width }
                            .pointerInput(message.id, durationMs) {
                                detectTapGestures(onTap = { offset -> seekFromX(offset.x) })
                            }
                            .pointerInput(message.id, durationMs) {
                                detectHorizontalDragGestures { change, _ ->
                                    seekFromX(change.position.x)
                                }
                            }
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = formatDurationMs(positionMs.toLong()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = formatDurationMs(
                                (message.audioDurationMs ?: durationMs.toLong()).coerceAtLeast(0L)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TransportSecondaryButton(
                            icon = Icons.Filled.SkipPrevious,
                            onClick = onSkipPrevious,
                            enabled = canSkipPrevious
                        )
                        Spacer(Modifier.width(20.dp))
                        TransportPrimaryButton(isPlaying = isPlaying, onPause = onPause, onResume = onResume)
                        Spacer(Modifier.width(20.dp))
                        TransportSecondaryButton(icon = Icons.Filled.SkipNext, onClick = onSkipNext)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun TransportPrimaryButton(
    isPlaying: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit
) {
    Surface(
        onClick = if (isPlaying) onPause else onResume,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary,
        modifier = Modifier.size(56.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun TransportSecondaryButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.colorScheme.onSurface
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.size(44.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun ConversationAvatarLocal(conversation: Conversation?, size: androidx.compose.ui.unit.Dp) {
    if (conversation != null) {
        CircleAvatar(conv = conversation, size = size)
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(size * 0.5f)
            )
        }
    }
}

