package com.autogram.app.ui.phone

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.R
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.MessageState
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.ui.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * v1.20.23 — full-screen pending-queue + Now Playing controller. Reached
 * by tapping the "▶ N Pending" button on Home. Replaces the old
 * tap-to-drain-immediately behaviour with a transparent list of what's
 * queued, plus a media-style transport in the Now Playing panel for
 * pause / skip-next / restart-current.
 */
@AndroidEntryPoint
class PendingQueueActivity : BaseLocaleActivity() {
    private val viewModel: PendingQueueViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutogramTheme {
                DebugScreenshotOverlay {
                    PendingQueueScreen(viewModel = viewModel, onBack = { finish() })
                }
            }
        }
    }
}

enum class QueueFilter { ALL, VOICE, TEXT }

@HiltViewModel
class PendingQueueViewModel @Inject constructor(
    messageRepository: MessageRepository,
    conversationRepository: ConversationRepository,
    private val playReplyUseCase: PlayReplyUseCase
) : ViewModel() {

    private val _filter = MutableStateFlow(QueueFilter.ALL)
    val filter: StateFlow<QueueFilter> = _filter.asStateFlow()

    /** Conversations indexed by id, used to resolve avatar/title for each queue row. */
    private val conversationsById: StateFlow<Map<String, Conversation>> =
        conversationRepository.observePinned()
            .map { list -> list.associateBy { it.id } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val queue: StateFlow<List<Message>> = combine(
        messageRepository.observePendingPlaybackQueue(),
        _filter
    ) { msgs, filter ->
        when (filter) {
            QueueFilter.ALL -> msgs
            QueueFilter.VOICE -> msgs.filter { !it.audioFilePath.isNullOrBlank() }
            QueueFilter.TEXT -> msgs.filter { it.audioFilePath.isNullOrBlank() }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val nowPlaying: StateFlow<Message?> =
        messageRepository.observeNowPlaying()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val playbackStatus: StateFlow<PlayReplyUseCase.PlaybackStatus> = playReplyUseCase.playbackStatus

    /** Conversation lookup for a message — exposed so the screen can render avatar + title. */
    fun conversationFor(message: Message): Conversation? = conversationsById.value[message.conversationId]

    fun setFilter(f: QueueFilter) { _filter.value = f }

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PendingQueueScreen(viewModel: PendingQueueViewModel, onBack: () -> Unit) {
    val queue by viewModel.queue.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val playback by viewModel.playbackStatus.collectAsState()
    val filter by viewModel.filter.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.pending_queue_title),
                            fontWeight = FontWeight.SemiBold
                        )
                        if (queue.isNotEmpty()) {
                            Spacer(Modifier.width(10.dp))
                            CountBadge(count = queue.size)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (queue.isNotEmpty()) {
                        PlayAllButton(onClick = { viewModel.playAll() })
                        Spacer(Modifier.width(8.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            FilterRow(
                selected = filter,
                onSelect = viewModel::setFilter,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (queue.isEmpty() && nowPlaying == null) {
                EmptyQueue(modifier = Modifier.weight(1f).fillMaxWidth())
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(queue, key = { it.id }) { msg ->
                        QueueRow(
                            message = msg,
                            conversation = viewModel.conversationFor(msg),
                            onClick = { viewModel.playFromHere(msg) }
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
}

@Composable
private fun CountBadge(count: Int) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.defaultMinSize(minWidth = 28.dp, minHeight = 28.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
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
        modifier = Modifier.height(40.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                stringResource(R.string.pending_queue_play_all),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun FilterRow(
    selected: QueueFilter,
    onSelect: (QueueFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChipPill(
            label = stringResource(R.string.pending_queue_filter_all),
            selected = selected == QueueFilter.ALL,
            onClick = { onSelect(QueueFilter.ALL) }
        )
        FilterChipPill(
            label = stringResource(R.string.pending_queue_filter_voice),
            selected = selected == QueueFilter.VOICE,
            onClick = { onSelect(QueueFilter.VOICE) }
        )
        FilterChipPill(
            label = stringResource(R.string.pending_queue_filter_text),
            selected = selected == QueueFilter.TEXT,
            onClick = { onSelect(QueueFilter.TEXT) }
        )
    }
}

@Composable
private fun FilterChipPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                       else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.height(32.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun QueueRow(
    message: Message,
    conversation: Conversation?,
    onClick: () -> Unit
) {
    val isVoice = !message.audioFilePath.isNullOrBlank()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ConversationAvatar(conversation = conversation, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation?.let { it.topicName?.takeIf { t -> t.isNotBlank() } ?: it.targetTitle }
                        ?: stringResource(R.string.main_default_primary_name),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (isVoice) {
                        stringResource(
                            R.string.pending_queue_row_voice,
                            formatDurationMs(message.audioDurationMs ?: 0L)
                        )
                    } else {
                        message.textContent?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.pending_queue_row_text_generic)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = TIME_FORMAT.format(Date(message.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary)
                )
            }
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

    // Poll position every 250ms while playing so the scrubber animates.
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
            // Header row with collapse/expand chevron.
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
                        ConversationAvatar(conversation = conversation, size = 44.dp)
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
private fun ConversationAvatar(conversation: Conversation?, size: androidx.compose.ui.unit.Dp) {
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

private val TIME_FORMAT = SimpleDateFormat("h:mm a", Locale.getDefault())

private fun formatDurationMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}
