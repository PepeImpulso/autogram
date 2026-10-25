package com.autogram.app.ui.phone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autogram.app.R
import com.autogram.app.domain.usecase.PlayReplyUseCase

enum class HistoryTab { PENDING, HISTORY }

/**
 * v1.21.0 — unified Pending + History screen as tabs.
 * v1.22.0 — both tabs now show messages cross-conversation with a
 * shared contact filter chip row at the top, avatar-on-left rows
 * (matching the Now Playing drawer's visual language), and a
 * per-row play/pause button so playback can always be reached
 * regardless of which conversation initiated it. The old per-conv
 * dropdown + chat-bubble layout is gone.
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
    // Badge count is the unfiltered total so the tab bar reflects the
    // whole pending queue rather than the user's current contact filter.
    val totalPending by pendingViewModel.totalQueue.collectAsState()
    var confirmMarkAll by remember { mutableStateOf(false) }

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
                    // Quick access from either tab: marking everything as
                    // listened is the most common action on this screen.
                    actions = {
                        IconButton(
                            onClick = { confirmMarkAll = true },
                            enabled = totalPending.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Filled.DoneAll,
                                stringResource(R.string.pending_queue_mark_all_listened)
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
                                if (totalPending.isNotEmpty()) {
                                    Spacer(Modifier.width(8.dp))
                                    TabCountBadge(count = totalPending.size)
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

    if (confirmMarkAll) {
        AlertDialog(
            onDismissRequest = { confirmMarkAll = false },
            title = { Text(stringResource(R.string.pending_queue_mark_all_confirm_title)) },
            text = { Text(stringResource(R.string.pending_queue_mark_all_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmMarkAll = false
                    pendingViewModel.markAllAsListened()
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
 * History tab body — cross-conversation message log filtered by contact.
 * Sorted newest-first. The "Clear" affordance only shows when a specific
 * contact is selected (clearing all conversations at once is too
 * destructive to surface as a single tap).
 */
@Composable
private fun HistoryTabContent(viewModel: HistoryViewModel) {
    val messages by viewModel.messages.collectAsState()
    val playback by viewModel.playbackStatus.collectAsState()
    val pinned by viewModel.pinned.collectAsState()
    val contactFilter by viewModel.contactFilter.collectAsState()
    val reportState by viewModel.reportState.collectAsState()
    val conversationsById = remember(pinned) { pinned.associateBy { it.id } }
    var clearConfirmOpen by remember { mutableStateOf(false) }

    TelegramReportDialogs(
        state = reportState,
        onChoose = viewModel::chooseReportOption,
        onSubmitDetails = viewModel::submitReportDetails,
        onDismiss = viewModel::dismissReport
    )

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
        ContactFilterRow(
            pinned = pinned,
            selectedId = contactFilter,
            onSelect = viewModel::setContactFilter,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )
        if (contactFilter != null && messages.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { clearConfirmOpen = true }) {
                    Icon(
                        Icons.Filled.DeleteSweep,
                        contentDescription = stringResource(R.string.history_clear_cd)
                    )
                }
            }
        }

        if (messages.isEmpty()) {
            HistoryEmptyState(modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    val pb = playback
                    val isPlayingThis = pb is PlayReplyUseCase.PlaybackStatus.Playing &&
                        pb.messageId == message.id
                    val isPausedThis = pb is PlayReplyUseCase.PlaybackStatus.Paused &&
                        pb.messageId == message.id
                    MessageRow(
                        message = message,
                        conversation = conversationsById[message.conversationId],
                        isPlayingThis = isPlayingThis,
                        isPausedThis = isPausedThis,
                        onPlay = { viewModel.onPlayClicked(message) },
                        onPause = viewModel::onPauseClicked,
                        onResume = viewModel::onResumeClicked,
                        positionMsProvider = { viewModel.currentPositionMs() },
                        durationMsProvider = { viewModel.durationMs() },
                        onSeek = viewModel::seekTo,
                        onReport = if (
                            message.direction == com.autogram.app.domain.model.MessageDirection.INCOMING &&
                            message.telegramMessageId != null
                        ) {
                            { viewModel.reportMessage(message) }
                        } else null
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
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
