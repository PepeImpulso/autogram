package com.autogram.app.ui.phone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
    fun skipNext() = viewModelScope.launch { playReplyUseCase.skipNext() }
    fun restartCurrent() = viewModelScope.launch { playReplyUseCase.restartCurrent() }

    fun currentPositionMs(): Int? = playReplyUseCase.currentPositionMs()
    fun durationMs(): Int? = playReplyUseCase.durationMs()
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
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                        onResume = viewModel::resume
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
            onResume = viewModel::resume,
            onSkipNext = { viewModel.skipNext() },
            onRestart = { viewModel.restartCurrent() }
        )
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
    onRestart: () -> Unit
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
                    Waveform(
                        messageId = message.id,
                        progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
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
                        TransportSecondaryButton(icon = Icons.Filled.SkipPrevious, onClick = onRestart)
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
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
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

private fun formatDurationMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}
