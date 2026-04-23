package com.autogram.app.ui.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import com.autogram.app.BuildConfig
import com.autogram.app.R
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.domain.usecase.SendVoiceMtprotoUseCase
import com.autogram.app.infrastructure.platform.BiometricGate
import com.autogram.app.infrastructure.telegram.mtproto.AuthState
import com.autogram.app.infrastructure.telegram.mtproto.TdLibClient
import com.autogram.app.service.MtprotoReceiverService
import com.autogram.app.ui.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : BaseLocaleActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var tdLibClient: TdLibClient

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            // Biometric gate: only prompts the first time in the app after
            // a long pause — BiometricGate enforces a grace window so
            // Ajustes ⇄ Inicio bounce doesn't trigger a prompt storm.
            val settings = settingsRepository.get()
            if (settings.biometricLockEnabled
                && BiometricGate.isSupported(this@MainActivity)
                && !BiometricGate.recentlyUnlocked()
            ) {
                // Block the onResume coroutine on the prompt outcome.
                // The old code fired the biometric prompt and fell
                // straight through to TDLib + MtprotoReceiverService
                // startup regardless of the result — an attacker who
                // picked up the phone had ~2-3 seconds of visible
                // Autogram UI before the finish() landed, and by then
                // the foreground service was already running and had
                // received / potentially played notifications.
                val authenticated = kotlinx.coroutines.CompletableDeferred<Boolean>()
                BiometricGate.ensureUnlocked(
                    activity = this@MainActivity,
                    title = getString(R.string.app_name),
                    subtitle = getString(R.string.biometric_unlock_subtitle),
                    onAuthenticated = { authenticated.complete(true) },
                    onFailed = { authenticated.complete(false) }
                )
                if (!authenticated.await()) {
                    finish()
                    return@launch
                }
            }
            tdLibClient.ensureStarted()
            val auth = tdLibClient.authState.first { it !is AuthState.Uninitialized && it !is AuthState.Initializing }
            if (auth !is AuthState.Ready) {
                val i = Intent(this@MainActivity, TelegramLoginActivity::class.java)
                i.flags = Intent.FLAG_ACTIVITY_NO_HISTORY
                startActivity(i)
                finish()
                return@launch
            }
            // First-run wizard. `onboardingCompleted` migrates to `true` for
            // upgrades so existing users aren't surprised; fresh installs see
            // false and get the wizard once.
            if (!settings.onboardingCompleted) {
                startActivity(Intent(this@MainActivity, OnboardingActivity::class.java))
            }
            startMtprotoReceiverService()
        }
    }

    private fun startMtprotoReceiverService() {
        val serviceIntent = Intent(this, MtprotoReceiverService::class.java).apply {
            action = MtprotoReceiverService.ACTION_START
        }
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Error starting MtprotoReceiverService")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutogramTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                        onOpenHistory = { startActivity(Intent(this, HistoryActivity::class.java)) },
                        onOpenStats = { startActivity(Intent(this, StatsActivity::class.java)) },
                        onManagePinned = { startActivity(Intent(this, ManagePinnedActivity::class.java)) },
                        onManageQuickReplies = { startActivity(Intent(this, QuickRepliesSettingsActivity::class.java)) }
                    )
                }
            }
        }
    }

    /**
     * Re-deliver the launch intent when the activity is brought to front
     * (e.g. user taps the QS tile while MainActivity is already in the
     * back-stack). Without this the second tap would never trigger
     * EXTRA_AUTO_RECORD because [MainScreen]'s LaunchedEffect only reads
     * the intent that was attached at activity-create time.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@Composable
private fun MainScreen(
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenStats: () -> Unit,
    onManagePinned: () -> Unit,
    onManageQuickReplies: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel()
) {
    val ctx = LocalContext.current
    val sendStatus by viewModel.sendStatus.collectAsState()
    val playback by viewModel.playbackStatus.collectAsState()
    val quickReplies by viewModel.quickReplies.collectAsState()
    val pinned by viewModel.pinned.collectAsState()
    val activeTargetId by viewModel.activeTargetId.collectAsState()
    val pendingCounts by viewModel.pendingReplyCounts.collectAsState()
    val outboxCount by viewModel.outboxCount.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val updateState by updateViewModel.state.collectAsState()
    val suggestions by viewModel.suggestedChips.collectAsState()
    val showPendingBadges by viewModel.showPendingBadges.collectAsState()
    val lastIncomingAudio by viewModel.lastIncomingWithAudio.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val selectedFolderId by viewModel.selectedFolderId.collectAsState()
    val quickReplyFilter by viewModel.quickReplyFilter.collectAsState()

    LaunchedEffect(Unit) {
        // Silent update check throttled: the ViewModel check() is cheap but we
        // only bother once per 6 h across app launches.
        val prefs = ctx.getSharedPreferences("autogram_updates", android.content.Context.MODE_PRIVATE)
        val last = prefs.getLong("last_check_ms", 0)
        val sixHours = 6L * 60 * 60 * 1000
        if (System.currentTimeMillis() - last > sixHours) {
            prefs.edit().putLong("last_check_ms", System.currentTimeMillis()).apply()
            updateViewModel.check(userInitiated = false)
        }
    }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    // QS-tile auto-record: if the activity was launched from the Quick
    // Settings tile (or the corresponding deep-link), kick off recording
    // to the primary target as soon as it's available + we have mic
    // permission. The intent extra is consumed (set to false) so a
    // configuration change or recomposition doesn't re-trigger.
    val pinnedForAuto by viewModel.pinned.collectAsState()
    LaunchedEffect(pinnedForAuto, hasMicPermission) {
        val activity = ctx as? android.app.Activity ?: return@LaunchedEffect
        val intent = activity.intent ?: return@LaunchedEffect
        if (!intent.getBooleanExtra(
                com.autogram.app.tiles.RecordVoiceTileService.EXTRA_AUTO_RECORD,
                false
            )
        ) return@LaunchedEffect
        // Guard against third-party apps crafting an Intent with
        // EXTRA_AUTO_RECORD to silently trigger voice recording on
        // Autogram launch. MainActivity is exported (LAUNCHER), so
        // without this check any app could start a mic session.
        // We accept the extra only when the intent came from our own
        // package — either our QS tile (RecordVoiceTileService) or
        // an internal re-launch (MainActivity → MainActivity via
        // onNewIntent). Falls back to safe (ignore extra) if we
        // can't determine the referrer.
        val referrer = activity.referrer?.host ?: activity.callingPackage
        if (referrer != null && referrer != activity.packageName) {
            timber.log.Timber.w("MainActivity: ignoring EXTRA_AUTO_RECORD from external caller '$referrer'")
            intent.removeExtra(com.autogram.app.tiles.RecordVoiceTileService.EXTRA_AUTO_RECORD)
            return@LaunchedEffect
        }
        val target = pinnedForAuto.firstOrNull { it.isActive } ?: return@LaunchedEffect
        if (!hasMicPermission) {
            // Wait for the user to grant; the LaunchedEffect re-runs when
            // hasMicPermission flips to true.
            return@LaunchedEffect
        }
        intent.removeExtra(com.autogram.app.tiles.RecordVoiceTileService.EXTRA_AUTO_RECORD)
        viewModel.startRecording(target.id)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMicPermission = granted }

    val primary = pinned.firstOrNull { it.isActive }
    // When a folder is selected, only its members (non-primary) appear in
    // the secondary row. Primary stays visible always — it's the user's
    // main target regardless of folder.
    val secondary = pinned.filter { !it.isActive }
        .let { all ->
            if (selectedFolderId == null) all
            else all.filter { it.folderId == selectedFolderId }
        }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text(ctx.getString(R.string.nav_home)) }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onManageQuickReplies,
                    icon = { Icon(Icons.Filled.Bolt, contentDescription = null) },
                    label = { Text(ctx.getString(R.string.nav_shortcuts)) }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onOpenHistory,
                    icon = { Icon(Icons.Filled.History, contentDescription = null) },
                    label = { Text(ctx.getString(R.string.history_title)) }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onOpenStats,
                    icon = { Icon(Icons.Filled.Insights, contentDescription = null) },
                    label = { Text(ctx.getString(R.string.stats_title)) }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onOpenSettings,
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(ctx.getString(R.string.settings_title)) }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HeroHeader(sendStatus = sendStatus, playback = playback, outboxCount = outboxCount, online = isOnline)

            if (folders.isNotEmpty()) {
                FolderTabRow(
                    folders = folders,
                    selectedFolderId = selectedFolderId,
                    onSelect = viewModel::selectFolder
                )
            }

            Spacer(Modifier.height(24.dp))

            if (suggestions.isNotEmpty()) {
                SuggestionChipsRow(
                    suggestions = suggestions,
                    onSuggestionTap = { text ->
                        viewModel.sendQuickReply(text, primary?.id)
                    },
                    onDismiss = { viewModel.dismissSuggestions() }
                )
                Spacer(Modifier.height(16.dp))
            }

            // Play-last-original button. Uses tertiaryContainer + a headphone
            // icon so it can't be confused with the quick-reply attajos (which
            // are filled-tonal buttons in the primary colour scheme).
            lastIncomingAudio?.let {
                PlayLastOriginalButton(onClick = viewModel::playLastOriginal)
                Spacer(Modifier.height(16.dp))
            }

            if (primary != null) {
                PrimaryRow(
                    primary = primary,
                    isRecordingThis = activeTargetId == primary.id &&
                        sendStatus is SendVoiceMtprotoUseCase.SendStatus.Recording,
                    pendingCount = if (showPendingBadges) (pendingCounts[primary.id] ?: 0) else 0,
                    hasPermission = hasMicPermission,
                    quickReplies = quickReplies,
                    quickReplyFilter = quickReplyFilter,
                    onFilterChange = viewModel::setQuickReplyFilter,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onStart = { viewModel.startRecording(primary.id) },
                    onSend = { viewModel.stopAndSend() },
                    onCancel = { viewModel.cancelRecording() },
                    onQuickReply = { viewModel.sendQuickReplyShortcut(it, primary.id) }
                )
            } else {
                NoPrimaryState(onConfigure = onManagePinned)
            }

            Spacer(Modifier.height(24.dp))

            SecondaryCirclesRow(
                circles = secondary,
                activeTargetId = activeTargetId,
                sendStatus = sendStatus,
                pendingCounts = if (showPendingBadges) pendingCounts else emptyMap(),
                hasPermission = hasMicPermission,
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onStart = { viewModel.startRecording(it.id) },
                onSend = { viewModel.stopAndSend() },
                onCancel = { viewModel.cancelRecording() },
                onManage = onManagePinned
            )

            Spacer(Modifier.weight(1f))
        }
    }

    // Update dialog overlay: available → dialog (user picks "Install now"
    // or "Later"); downloading → tiny progress banner; error → banner with
    // "Open Settings" shortcut so the user can retry from the full Updates
    // screen if auto-install fails (e.g. the install-packages permission
    // prompt was denied).
    when (val s = updateState) {
        is UpdateViewModel.State.Available -> {
            UpdateDialog(
                state = s,
                currentVersion = updateViewModel.currentVersion,
                onInstall = { updateViewModel.installNow() },
                onDismiss = { updateViewModel.dismiss() }
            )
        }
        is UpdateViewModel.State.Downloading -> UpdateBanner(
            text = stringResource(R.string.update_downloading_banner),
            showSpinner = true,
            actionLabel = null,
            onAction = {}
        )
        is UpdateViewModel.State.Error -> UpdateBanner(
            text = stringResource(R.string.update_error_banner),
            showSpinner = false,
            actionLabel = stringResource(R.string.update_error_action),
            onAction = {
                updateViewModel.dismiss()
                onOpenSettings()
            }
        )
        else -> Unit
    }

    // Preview-before-send dialog. Appears right after the user releases the
    // record button when Settings → Preview-before-send is on.
    val previewOpen = sendStatus is SendVoiceMtprotoUseCase.SendStatus.PreviewReady ||
        sendStatus is SendVoiceMtprotoUseCase.SendStatus.PreviewPlaying
    if (previewOpen) {
        val snap = sendStatus  // snapshot to allow smart casts
        val isPlaying = snap is SendVoiceMtprotoUseCase.SendStatus.PreviewPlaying
        val durationSec = (snap as? SendVoiceMtprotoUseCase.SendStatus.PreviewReady)?.durationMs?.div(1000)
        AlertDialog(
            onDismissRequest = { viewModel.cancelPreview() },
            title = { Text(stringResource(R.string.preview_title)) },
            text = {
                Text(
                    "${stringResource(R.string.record_mic)} · ${durationSec?.let { "${it}s" } ?: "…"}",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmPreview() }) {
                    Text(stringResource(R.string.preview_send))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = { viewModel.previewPlay() },
                        enabled = !isPlaying
                    ) { Text(stringResource(R.string.preview_play)) }
                    TextButton(onClick = { viewModel.cancelPreview() }) {
                        Text(stringResource(R.string.preview_retry))
                    }
                }
            }
        )
    }
}

@Composable
private fun HeroHeader(
    sendStatus: SendVoiceMtprotoUseCase.SendStatus,
    playback: PlayReplyUseCase.PlaybackStatus,
    outboxCount: Int,
    online: Boolean
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.autogram_logo),
            contentDescription = null,
            modifier = Modifier.size(32.dp)
        )
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        StatusPill(sendStatus, playback, outboxCount, online)
    }
}

@Composable
private fun StatusPill(
    sendStatus: SendVoiceMtprotoUseCase.SendStatus,
    playback: PlayReplyUseCase.PlaybackStatus,
    outboxCount: Int,
    online: Boolean
) {
    data class Pill(val text: String, val container: Color, val onContainer: Color)
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val pill = when (sendStatus) {
        is SendVoiceMtprotoUseCase.SendStatus.Recording ->
            Pill(ctx.getString(R.string.status_recording), scheme.errorContainer, scheme.onErrorContainer)
        is SendVoiceMtprotoUseCase.SendStatus.Validating ->
            Pill(ctx.getString(R.string.status_validating), scheme.tertiaryContainer, scheme.onTertiaryContainer)
        is SendVoiceMtprotoUseCase.SendStatus.Sending ->
            Pill(ctx.getString(R.string.status_sending), scheme.primaryContainer, scheme.onPrimaryContainer)
        is SendVoiceMtprotoUseCase.SendStatus.Sent ->
            Pill(ctx.getString(R.string.status_sent), scheme.primaryContainer, scheme.onPrimaryContainer)
        is SendVoiceMtprotoUseCase.SendStatus.QueuedOffline -> {
            // The SendStatus.QueuedOffline value is set when the user taps
            // send while offline; RetryWorker later drains the outbox but
            // has no channel back to reset this field. So instead of
            // trusting SendStatus alone, we gate on the outbox count:
            //   - outbox == 0 → worker finished, fall through to Idle.
            //   - outbox > 0 && !online → still queued.
            //   - outbox > 0 && online → worker is retrying right now.
            when {
                outboxCount == 0 ->
                    Pill(ctx.getString(R.string.status_idle), scheme.surfaceVariant, scheme.onSurfaceVariant)
                !online ->
                    Pill(ctx.getString(R.string.status_queued_offline_count, outboxCount), scheme.tertiaryContainer, scheme.onTertiaryContainer)
                else ->
                    Pill(ctx.getString(R.string.status_sending_queue, outboxCount), scheme.primaryContainer, scheme.onPrimaryContainer)
            }
        }
        is SendVoiceMtprotoUseCase.SendStatus.Discarded ->
            Pill(ctx.getString(R.string.status_discarded, sendStatus.reason), scheme.tertiaryContainer, scheme.onTertiaryContainer)
        is SendVoiceMtprotoUseCase.SendStatus.Error ->
            Pill(ctx.getString(R.string.status_error_generic, sendStatus.message), scheme.errorContainer, scheme.onErrorContainer)
        else -> {
            // Idle priority: outbox count > pending playback count > just "ready".
            when {
                outboxCount > 0 ->
                    Pill(ctx.getString(R.string.status_outbox_count, outboxCount), scheme.tertiaryContainer, scheme.onTertiaryContainer)
                (playback as? PlayReplyUseCase.PlaybackStatus.PendingCount)?.count?.let { it > 0 } == true -> {
                    val c = (playback as PlayReplyUseCase.PlaybackStatus.PendingCount).count
                    Pill(ctx.getString(R.string.status_pending_count, c), scheme.tertiaryContainer, scheme.onTertiaryContainer)
                }
                else -> Pill(ctx.getString(R.string.status_idle), scheme.surfaceVariant, scheme.onSurfaceVariant)
            }
        }
    }
    Surface(
        color = pill.container,
        contentColor = pill.onContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.padding(horizontal = 24.dp)
    ) {
        Text(
            text = pill.text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Horizontal scrollable tab row above the grid when the user has at least
 * one folder. First tab is always "All" (selectedFolderId = null), rest are
 * per-folder filters. Uses LazyRow of FilterChips rather than ScrollableTabRow
 * so the visual weight is lighter — these are soft filters, not navigation.
 */
@Composable
private fun FolderTabRow(
    folders: List<com.autogram.app.domain.model.CircleFolder>,
    selectedFolderId: String?,
    onSelect: (String?) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "__all") {
            androidx.compose.material3.FilterChip(
                selected = selectedFolderId == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.main_tab_all)) }
            )
        }
        items(folders, key = { it.id }) { folder ->
            androidx.compose.material3.FilterChip(
                selected = selectedFolderId == folder.id,
                onClick = { onSelect(folder.id) },
                label = { Text(folder.name) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(androidx.compose.material3.FilterChipDefaults.IconSize)
                    )
                }
            )
        }
    }
}

/**
 * Outlined pill showing "Play last original" — appears when summary mode is
 * on or when a previously-received voice note is still on disk. Deliberately
 * styled with tertiaryContainer so it reads as a secondary affordance, not
 * a quick-reply attajo.
 */
@Composable
private fun PlayLastOriginalButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
        ),
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Filled.Headphones,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.main_play_last_original),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

/**
 * Ephemeral chips suggesting short replies to the last incoming message.
 * Populated by [SuggestQuickReplyUseCase]; tap sends via the active target's
 * quick-reply path. The user can dismiss the whole row with the × affordance
 * if none of the suggestions match their intent.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SuggestionChipsRow(
    suggestions: List<com.autogram.app.domain.usecase.SuggestQuickReplyUseCase.Suggestion>,
    onSuggestionTap: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(suggestions, key = { it.ruleId + "-" + it.text }) { suggestion ->
                AssistChip(
                    onClick = { onSuggestionTap(suggestion.text) },
                    label = { Text(suggestion.text, maxLines = 1) }
                )
            }
        }
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.common_dismiss),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Ephemeral banner above the bottom nav for the Downloading / Error update
 * states. Available state uses the richer [UpdateDialog] instead.
 */
@Composable
private fun UpdateBanner(
    text: String,
    showSpinner: Boolean,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showSpinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (!actionLabel.isNullOrBlank()) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun NoPrimaryState(onConfigure: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 32.dp)
    ) {
        Text(
            ctx.getString(R.string.main_no_primary_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onConfigure) {
            Text(ctx.getString(R.string.main_configure_shortcuts))
        }
    }
}

/**
 * Circular record button — long-press to record, release to send, drag out to
 * cancel. When [conversation]'s photo is set it shows as the circle background,
 * with the mic icon overlayed on a translucent dark scrim while recording.
 */
@Composable
private fun RecordCircle(
    conversation: Conversation,
    size: Dp,
    isRecordingThis: Boolean,
    pendingCount: Int,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isRecordingThis) 1.1f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "circle-scale"
    )
    val haloColor = MaterialTheme.colorScheme.error.copy(alpha = if (isRecordingThis) 0.25f else 0f)

    Box(modifier = Modifier.size(size + 24.dp)) {
        // Pulse halo when recording
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(size + 24.dp)
                .scale(scale * 1.08f)
                .clip(CircleShape)
                .background(haloColor)
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(size)
                .scale(scale)
                .clip(CircleShape)
                .pointerInput(hasPermission, conversation.id) {
                    if (!hasPermission) {
                        awaitEachGesture {
                            awaitFirstDown()
                            onRequestPermission()
                        }
                        return@pointerInput
                    }
                    awaitEachGesture {
                        awaitFirstDown()
                        onStart()
                        var cancelled = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull()
                            if (change == null || !change.pressed) break
                            val pos = change.position
                            val outside = pos.x < 0 || pos.y < 0 ||
                                pos.x > this.size.width || pos.y > this.size.height
                            if (outside && !cancelled) {
                                cancelled = true
                                onCancel()
                                break
                            }
                        }
                        if (!cancelled) onSend()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            CircleAvatar(conv = conversation, size = size)
            if (isRecordingThis) {
                // Recording: full red scrim + centred close icon take over so the
                // user clearly sees which circle is live.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.55f))
                )
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.record_cancel),
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.4f)
                )
            } else {
                // Idle: mic lives in a small bottom-right badge so the user's
                // custom background photo stays fully visible.
                MicBadge(
                    circleSize = size,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }
        }
        if (pendingCount > 0) {
            PendingBadge(
                count = pendingCount,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }
}

@Composable
private fun MicBadge(circleSize: Dp, modifier: Modifier = Modifier) {
    val badgeSize = (circleSize * 0.32f).coerceAtLeast(28.dp)
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = Color.White,
        shadowElevation = 4.dp,
        modifier = modifier.size(badgeSize)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = stringResource(R.string.record_mic),
                modifier = Modifier.size(badgeSize * 0.6f)
            )
        }
    }
}

@Composable
private fun PendingBadge(count: Int, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
        shadowElevation = 2.dp,
        modifier = modifier.defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp)) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecondaryCirclesRow(
    circles: List<Conversation>,
    activeTargetId: String?,
    sendStatus: SendVoiceMtprotoUseCase.SendStatus,
    pendingCounts: Map<String, Int>,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onStart: (Conversation) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onManage: () -> Unit
) {
    // Wrapping grid — circles flow onto a new row once the current row fills
    // up. Keeps the layout clean up to ~8 circles on a phone-width screen
    // without horizontal scrolling.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        circles.forEach { conv ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RecordCircle(
                    conversation = conv,
                    size = 72.dp,
                    isRecordingThis = activeTargetId == conv.id &&
                        sendStatus is SendVoiceMtprotoUseCase.SendStatus.Recording,
                    pendingCount = pendingCounts[conv.id] ?: 0,
                    hasPermission = hasPermission,
                    onRequestPermission = onRequestPermission,
                    onStart = { onStart(conv) },
                    onSend = onSend,
                    onCancel = onCancel
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    conv.topicName ?: conv.targetTitle ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
        // "+" button — always visible, opens the manage screen.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    IconButton(onClick = onManage) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.main_manage_shortcuts_cd),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.main_add_shortcut),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Primary target on the left (Merlin circle + name), quick-reply shortcuts
 * on the right. Shortcuts send voice-less text to the primary conversation
 * — no selector needed, they always go to Merlin.
 */
@Composable
private fun PrimaryRow(
    primary: Conversation,
    isRecordingThis: Boolean,
    pendingCount: Int,
    hasPermission: Boolean,
    quickReplies: List<com.autogram.app.domain.model.QuickReply>,
    quickReplyFilter: com.autogram.app.domain.model.QuickReplyFilter,
    onFilterChange: (com.autogram.app.domain.model.QuickReplyFilter) -> Unit,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onQuickReply: (com.autogram.app.domain.model.QuickReply) -> Unit
) {
    val hasBothKinds = remember(quickReplies) {
        quickReplies.any { it.kind == com.autogram.app.domain.model.QuickReplyKind.TEXT } &&
        quickReplies.any { it.kind == com.autogram.app.domain.model.QuickReplyKind.VOICE }
    }
    val visible = remember(quickReplies, quickReplyFilter) {
        when (quickReplyFilter) {
            com.autogram.app.domain.model.QuickReplyFilter.ALL -> quickReplies
            com.autogram.app.domain.model.QuickReplyFilter.TEXT ->
                quickReplies.filter { it.kind == com.autogram.app.domain.model.QuickReplyKind.TEXT }
            com.autogram.app.domain.model.QuickReplyFilter.VOICE ->
                quickReplies.filter { it.kind == com.autogram.app.domain.model.QuickReplyKind.VOICE }
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        if (hasBothKinds) {
            QuickReplyFilterRow(
                selected = quickReplyFilter,
                onSelect = onFilterChange,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RecordCircle(
                    conversation = primary,
                    size = 176.dp,
                    isRecordingThis = isRecordingThis,
                    pendingCount = pendingCount,
                    hasPermission = hasPermission,
                    onRequestPermission = onRequestPermission,
                    onStart = onStart,
                    onSend = onSend,
                    onCancel = onCancel
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    primary.targetTitle ?: stringResource(R.string.main_default_primary_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                primary.topicName?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                visible.forEach { r ->
                    QuickReplyChipButton(
                        reply = r,
                        onTap = { onQuickReply(r) }
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickReplyFilterRow(
    selected: com.autogram.app.domain.model.QuickReplyFilter,
    onSelect: (com.autogram.app.domain.model.QuickReplyFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        com.autogram.app.domain.model.QuickReplyFilter.values().forEach { f ->
            val labelRes = when (f) {
                com.autogram.app.domain.model.QuickReplyFilter.ALL -> R.string.quick_replies_filter_all
                com.autogram.app.domain.model.QuickReplyFilter.TEXT -> R.string.quick_replies_filter_text
                com.autogram.app.domain.model.QuickReplyFilter.VOICE -> R.string.quick_replies_filter_voice
            }
            androidx.compose.material3.FilterChip(
                selected = selected == f,
                onClick = { onSelect(f) },
                label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

@Composable
private fun QuickReplyChipButton(
    reply: com.autogram.app.domain.model.QuickReply,
    onTap: () -> Unit
) {
    val isVoice = reply.kind == com.autogram.app.domain.model.QuickReplyKind.VOICE
    FilledTonalButton(
        onClick = onTap,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = if (isVoice) androidx.compose.material.icons.Icons.Filled.Mic
                          else androidx.compose.material.icons.Icons.Filled.Bolt,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            reply.label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}
