package com.autogram.app.ui.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ShortText
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autogram.app.R
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.MessageState
import com.autogram.app.domain.model.SummaryState
import com.autogram.app.domain.model.TranscriptionState
import com.autogram.app.domain.usecase.PlayReplyUseCase
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class HistoryTab { PENDING, HISTORY }

/**
 * v1.21.0 — unified Pending + History screen. Hosts both views as tabs so the
 * user always has a stable entry point to the pending playback queue (the
 * old standalone PendingQueueActivity was reachable only from the home
 * banner, which disappears when no messages are pending). Playback state
 * is shared via the singleton [PlayReplyUseCase] so starting playback in
 * one tab and switching to the other does not interrupt audio.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryWithPendingTabsScreen(
    historyViewModel: HistoryViewModel,
    pendingViewModel: PendingQueueViewModel,
    initialTab: HistoryTab,
    onBack: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
    val pendingCount by pendingViewModel.queue.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.history_title),
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                stringResource(R.string.common_back)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
                TabRow(
                    selectedTabIndex = if (selectedTab == HistoryTab.PENDING) 0 else 1,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Tab(
                        selected = selectedTab == HistoryTab.PENDING,
                        onClick = { selectedTab = HistoryTab.PENDING },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.history_tab_pending))
                                if (pendingCount.isNotEmpty()) {
                                    Spacer(Modifier.width(8.dp))
                                    TabCountBadge(count = pendingCount.size)
                                }
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == HistoryTab.HISTORY,
                        onClick = { selectedTab = HistoryTab.HISTORY },
                        text = { Text(stringResource(R.string.history_tab_history)) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                HistoryTab.PENDING -> PendingQueueTabContent(viewModel = pendingViewModel)
                HistoryTab.HISTORY -> HistoryTabContent(viewModel = historyViewModel)
            }
        }
    }
}

@Composable
private fun TabCountBadge(count: Int) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp)
            )
        }
    }
}

/**
 * History tab body — message list grouped by day. The conversation chooser
 * (formerly in the TopAppBar) is now an inline header row at the top of
 * this tab's body so the parent tab host can stay generic.
 */
@Composable
private fun HistoryTabContent(viewModel: HistoryViewModel) {
    val messages by viewModel.messages.collectAsState()
    val playback by viewModel.playbackStatus.collectAsState()
    val conversation by viewModel.conversation.collectAsState()
    val pinned by viewModel.pinned.collectAsState()
    var dropdownOpen by remember { mutableStateOf(false) }
    var clearConfirmOpen by remember { mutableStateOf(false) }

    if (clearConfirmOpen) {
        AlertDialog(
            onDismissRequest = { clearConfirmOpen = false },
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(stringResource(R.string.history_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistory()
                    clearConfirmOpen = false
                }) {
                    Text(stringResource(R.string.history_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirmOpen = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (pinned.size > 1) {
                        Modifier.clickable { dropdownOpen = true }
                    } else Modifier
                ) {
                    val subtitle = conversation?.let { conv ->
                        conv.topicName?.takeIf { it.isNotBlank() }
                            ?: conv.targetTitle?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.main_default_primary_name)
                    } ?: stringResource(R.string.main_default_primary_name)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (pinned.size > 1) {
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = stringResource(R.string.history_choose_conversation),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                    }
                }
                DropdownMenu(
                    expanded = dropdownOpen,
                    onDismissRequest = { dropdownOpen = false }
                ) {
                    pinned.forEach { conv ->
                        val label = conv.topicName?.takeIf { it.isNotBlank() }
                            ?: conv.targetTitle?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.main_default_primary_name)
                        val isCurrent = conv.id == conversation?.id
                        DropdownMenuItem(
                            text = {
                                Text(
                                    label,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold
                                                 else FontWeight.Normal
                                )
                            },
                            onClick = {
                                viewModel.selectConversation(conv.id)
                                dropdownOpen = false
                            }
                        )
                    }
                }
            }
            if (messages.isNotEmpty()) {
                IconButton(onClick = { clearConfirmOpen = true }) {
                    Icon(
                        Icons.Filled.DeleteSweep,
                        stringResource(R.string.history_clear_cd)
                    )
                }
            }
        }

        if (messages.isEmpty()) {
            HistoryEmptyState(modifier = Modifier.fillMaxSize())
            return
        }

        // Reverse-chronological — newest at top so a fresh reply is the first
        // thing the user sees, without scrolling. Day groups inherit the
        // ordering: Today first, then Yesterday, then older.
        val ordered = remember(messages) { messages.sortedByDescending { it.createdAt } }
        val grouped = groupByDay(ordered)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            grouped.forEach { (dayLabel, items) ->
                item(key = "header-$dayLabel") { DayDivider(dayLabel) }
                items(items, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        playback = playback,
                        onPlay = { viewModel.onPlayClicked(message) },
                        onPause = { viewModel.onPauseClicked() },
                        onResume = { viewModel.onResumeClicked() }
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryEmptyState(modifier: Modifier = Modifier) {
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
                    Icons.Filled.Forum,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.history_empty),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.history_empty_sub),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

@Composable
private fun DayDivider(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    playback: PlayReplyUseCase.PlaybackStatus,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit
) {
    val isOutgoing = message.direction == MessageDirection.OUTGOING
    val hasAudio = message.audioFilePath != null
    val time = remember(message.createdAt) { timeFormat.format(Date(message.createdAt)) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = bubbleShape(isOutgoing),
                color = if (isOutgoing) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                tonalElevation = if (isOutgoing) 0.dp else 2.dp,
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    if (hasAudio && !isOutgoing) {
                        AudioBubbleHeader(
                            message = message,
                            playback = playback,
                            onPlay = onPlay,
                            onPause = onPause,
                            onResume = onResume
                        )
                    } else if (hasAudio && isOutgoing) {
                        OutgoingAudioHeader(durationMs = message.audioDurationMs)
                    }
                    message.textContent?.takeIf { it.isNotBlank() }?.let { txt ->
                        if (hasAudio) Spacer(Modifier.height(6.dp))
                        Text(
                            txt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (!hasAudio && message.textContent.isNullOrBlank()) {
                        Text(
                            stringResource(R.string.history_no_content),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    SummaryInlineBlock(message)
                    TranscriptionInlineBlock(message)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        stateLabelOrNull(message.state)?.let { label ->
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = stateColor(message.state)
                            )
                            Text(
                                "·",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            time,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioBubbleHeader(
    message: Message,
    playback: PlayReplyUseCase.PlaybackStatus,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit
) {
    val isPlayingThis = playback is PlayReplyUseCase.PlaybackStatus.Playing && playback.messageId == message.id
    val isPausedThis = playback is PlayReplyUseCase.PlaybackStatus.Paused && playback.messageId == message.id
    val duration = formatDuration(message.audioDurationMs)
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledIconButton(
            onClick = when {
                isPlayingThis -> onPause
                isPausedThis -> onResume
                else -> onPlay
            },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = if (isPlayingThis) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlayingThis) stringResource(R.string.playback_pause_cd)
                                     else stringResource(R.string.playback_play_cd)
            )
        }
        Spacer(Modifier.width(10.dp))
        Waveform(
            messageId = message.id,
            progress = if (isPlayingThis) 0.5f else 0f,
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            duration,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OutgoingAudioHeader(durationMs: Long?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.history_voice_note, formatDuration(durationMs)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun SummaryInlineBlock(message: Message) {
    when (message.summaryState) {
        SummaryState.NONE, SummaryState.UNAVAILABLE -> Unit
        SummaryState.PENDING,
        SummaryState.IN_PROGRESS -> {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.history_summary_in_progress),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        SummaryState.DONE -> message.summary?.let { text ->
            Spacer(Modifier.height(8.dp))
            InlineBlock(
                icon = Icons.Filled.Summarize,
                label = stringResource(R.string.history_summary_label),
                tint = MaterialTheme.colorScheme.tertiary,
                background = MaterialTheme.colorScheme.tertiaryContainer,
                onBackground = MaterialTheme.colorScheme.onTertiaryContainer,
                body = text,
                timestamp = formatBubbleTime(message.createdAt)
            )
        }
        SummaryState.FAILED -> {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.history_summary_failed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun TranscriptionInlineBlock(message: Message) {
    if (message.audioFilePath == null) return
    when (message.transcriptionState) {
        TranscriptionState.NONE -> Unit
        TranscriptionState.PENDING,
        TranscriptionState.IN_PROGRESS -> {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.history_transcription_in_progress),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TranscriptionState.DONE -> message.transcription?.let { text ->
            Spacer(Modifier.height(8.dp))
            InlineBlock(
                icon = Icons.AutoMirrored.Filled.ShortText,
                label = stringResource(R.string.history_transcription_label),
                tint = MaterialTheme.colorScheme.primary,
                background = MaterialTheme.colorScheme.surfaceContainerHigh,
                onBackground = MaterialTheme.colorScheme.onSurface,
                body = text,
                timestamp = formatBubbleTime(message.createdAt)
            )
        }
        TranscriptionState.UNAVAILABLE -> {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.history_transcription_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TranscriptionState.FAILED -> {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.history_transcription_failed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun InlineBlock(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String?,
    tint: Color,
    background: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    onBackground: Color = MaterialTheme.colorScheme.onSurface,
    body: String,
    timestamp: String? = null
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = background,
        contentColor = onBackground,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(14.dp)
                )
                if (label != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = tint
                    )
                }
            }
            if (label != null) Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = onBackground
            )
            if (timestamp != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }
        }
    }
}

private fun bubbleShape(outgoing: Boolean): RoundedCornerShape =
    if (outgoing) RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 18.dp)
    else RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 4.dp)

private fun formatBubbleTime(ms: Long): String = timeFormat.format(Date(ms))

private fun formatDuration(ms: Long?): String {
    if (ms == null || ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

@Composable
private fun stateLabelOrNull(state: MessageState): String? = when (state) {
    MessageState.SENT -> stringResource(R.string.history_state_sent) + " ✓"
    MessageState.SENDING -> stringResource(R.string.history_state_sending)
    MessageState.QUEUED -> stringResource(R.string.history_state_queued)
    MessageState.QUEUED_OFFLINE -> stringResource(R.string.history_state_queued_offline)
    MessageState.FAILED -> stringResource(R.string.history_state_failed) + " ✗"
    MessageState.PERMANENTLY_FAILED -> stringResource(R.string.history_state_failed_permanent) + " ✗"
    MessageState.RECEIVED -> stringResource(R.string.history_state_received)
    MessageState.PENDING_PLAYBACK -> stringResource(R.string.history_state_pending_playback)
    MessageState.PLAYING -> stringResource(R.string.history_state_playing)
    MessageState.PAUSED -> stringResource(R.string.history_state_paused)
    MessageState.PLAYED -> stringResource(R.string.history_state_played) + " ✓"
    MessageState.IDLE,
    MessageState.RECORDING,
    MessageState.RECORDED,
    MessageState.VALIDATING,
    MessageState.DISCARDED,
    MessageState.ARCHIVED -> null
}

@Composable
private fun stateColor(state: MessageState) = when (state) {
    MessageState.SENT, MessageState.PLAYED -> MaterialTheme.colorScheme.primary
    MessageState.FAILED, MessageState.PERMANENTLY_FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private val timeFormat: SimpleDateFormat by lazy {
    SimpleDateFormat("HH:mm", Locale.getDefault())
}

private val dayFormat: SimpleDateFormat by lazy {
    SimpleDateFormat("EEEE d MMMM", Locale.getDefault())
}

@Composable
private fun groupByDay(messages: List<Message>): List<Pair<String, List<Message>>> {
    val today = stringResource(R.string.history_today)
    val yesterday = stringResource(R.string.history_yesterday)
    val cal = Calendar.getInstance()
    val todayDay = cal.get(Calendar.DAY_OF_YEAR)
    val todayYear = cal.get(Calendar.YEAR)
    cal.add(Calendar.DAY_OF_YEAR, -1)
    val yestDay = cal.get(Calendar.DAY_OF_YEAR)
    val yestYear = cal.get(Calendar.YEAR)

    fun label(ts: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        val d = c.get(Calendar.DAY_OF_YEAR)
        val y = c.get(Calendar.YEAR)
        return when {
            y == todayYear && d == todayDay -> today
            y == yestYear && d == yestDay -> yesterday
            else -> dayFormat.format(Date(ts))
        }
    }
    return messages
        .groupBy { label(it.createdAt) }
        .map { (k, v) -> k to v }
}
