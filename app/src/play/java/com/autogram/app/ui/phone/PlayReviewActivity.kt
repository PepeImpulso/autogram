package com.autogram.app.ui.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.infrastructure.platform.IncomingMessageNotifier
import com.autogram.app.infrastructure.review.PlayReviewModeStore
import com.autogram.app.service.MtprotoReceiverService
import com.autogram.app.ui.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Reusable, local-only account that gives Google Play reviewers safe app access. */
@AndroidEntryPoint
class PlayReviewActivity : BaseLocaleActivity() {
    @Inject lateinit var dataSeeder: SyntheticReviewDataSeeder
    @Inject lateinit var notifier: IncomingMessageNotifier
    @Inject lateinit var playReply: PlayReplyUseCase
    @Inject lateinit var reviewModeStore: PlayReviewModeStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            reviewModeStore.activate()
            withContext(Dispatchers.IO) { dataSeeder.seed(clearExisting = false) }
            render()
        }
    }

    private fun render() {
        setContent {
            AutogramTheme {
                val playbackStatus by playReply.playbackStatus.collectAsState()
                var pendingPermissionAction by remember {
                    mutableStateOf<ReviewPermissionAction?>(null)
                }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        when (pendingPermissionAction) {
                            ReviewPermissionAction.POST_MESSAGE -> postDemoNotification()
                            ReviewPermissionAction.START_RECEIVER -> setReceiverRunning(true)
                            ReviewPermissionAction.PLAY_MEDIA -> playDemoMedia()
                            null -> Unit
                        }
                    }
                    pendingPermissionAction = null
                }
                PlayReviewDemo(
                    playbackStatus = when (playbackStatus) {
                        is PlayReplyUseCase.PlaybackStatus.Playing -> "Playing synthetic TTS message"
                        is PlayReplyUseCase.PlaybackStatus.Paused -> "Paused"
                        else -> "Stopped"
                    },
                    onPostNotification = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            pendingPermissionAction = ReviewPermissionAction.POST_MESSAGE
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            postDemoNotification()
                        }
                    },
                    onStartReceiver = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            pendingPermissionAction = ReviewPermissionAction.START_RECEIVER
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            setReceiverRunning(true)
                        }
                    },
                    onStopReceiver = { setReceiverRunning(false) },
                    onPlayMedia = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            pendingPermissionAction = ReviewPermissionAction.PLAY_MEDIA
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            playDemoMedia()
                        }
                    },
                    onStopMedia = { playReply.stopAll() },
                    onOpenShortcuts = {
                        startActivity(Intent(this, ManagePinnedActivity::class.java))
                    },
                    onExit = {
                        playReply.stopAll()
                        setReceiverRunning(false)
                        reviewModeStore.deactivate()
                        startActivity(
                            Intent(this, TelegramLoginActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        )
                        finish()
                    }
                )
            }
        }
    }

    private fun postDemoNotification() {
        notifier.postTextReceived(
            conversation = SyntheticReviewDataSeeder.primaryConversation(),
            preview = "The Play review message is ready for hands-free reply.",
            timestampMs = System.currentTimeMillis(),
            messageId = "store-message-1"
        )
    }

    private fun setReceiverRunning(start: Boolean) {
        val intent = Intent(this, MtprotoReceiverService::class.java).apply {
            action = if (start) MtprotoReceiverService.ACTION_START else MtprotoReceiverService.ACTION_STOP
        }
        if (start) ContextCompat.startForegroundService(this, intent) else startService(intent)
    }

    private fun playDemoMedia() {
        setReceiverRunning(true)
        lifecycleScope.launch {
            delay(250)
            playReply.playTextManually(SyntheticReviewDataSeeder.playbackMessage())
        }
    }
}

private enum class ReviewScreen { HOME, HISTORY, STATS, SETTINGS, RULES, TOOLS }
private enum class ReviewPermissionAction { POST_MESSAGE, START_RECEIVER, PLAY_MEDIA }

@Composable
private fun PlayReviewDemo(
    playbackStatus: String,
    onPostNotification: () -> Unit,
    onStartReceiver: () -> Unit,
    onStopReceiver: () -> Unit,
    onPlayMedia: () -> Unit,
    onStopMedia: () -> Unit,
    onOpenShortcuts: () -> Unit,
    onExit: () -> Unit
) {
    var screen by remember { mutableStateOf(ReviewScreen.HOME) }
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Google Play review demo · synthetic data",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(onClick = { screen = ReviewScreen.TOOLS }) { Text("Tools") }
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            when (screen) {
                ReviewScreen.HOME -> MainScreen(
                    onOpenSettings = { screen = ReviewScreen.SETTINGS },
                    onOpenHistory = { screen = ReviewScreen.HISTORY },
                    onOpenHistoryFor = { screen = ReviewScreen.HISTORY },
                    onOpenStats = { screen = ReviewScreen.STATS },
                    onOpenPendingQueue = { screen = ReviewScreen.HISTORY },
                    onManagePinned = onOpenShortcuts,
                    onManageQuickReplies = { screen = ReviewScreen.TOOLS },
                    messagingActionsEnabled = false
                )
                ReviewScreen.HISTORY -> HistoryWithPendingTabsScreen(
                    historyViewModel = hiltViewModel(),
                    pendingViewModel = hiltViewModel(),
                    initialTab = HistoryTab.HISTORY,
                    onBack = { screen = ReviewScreen.HOME }
                )
                ReviewScreen.STATS -> StatsScreen(onBack = { screen = ReviewScreen.HOME })
                ReviewScreen.SETTINGS -> SettingsScreen(
                    viewModel = hiltViewModel(),
                    onBack = { screen = ReviewScreen.HOME }
                )
                ReviewScreen.RULES -> Surface(modifier = Modifier.fillMaxSize()) {
                    AcceptableUseScreen(
                        onAccept = { screen = ReviewScreen.HOME },
                        onExit = { screen = ReviewScreen.HOME }
                    )
                }
                ReviewScreen.TOOLS -> ReviewToolsScreen(
                    onHome = { screen = ReviewScreen.HOME },
                    onHistory = { screen = ReviewScreen.HISTORY },
                    onStats = { screen = ReviewScreen.STATS },
                    onSettings = { screen = ReviewScreen.SETTINGS },
                    onRules = { screen = ReviewScreen.RULES },
                    onPostNotification = onPostNotification,
                    onStartReceiver = onStartReceiver,
                    onStopReceiver = onStopReceiver,
                    onPlayMedia = onPlayMedia,
                    onStopMedia = onStopMedia,
                    playbackStatus = playbackStatus,
                    onOpenShortcuts = onOpenShortcuts,
                    onExit = onExit
                )
            }
        }
    }
}

@Composable
private fun ReviewToolsScreen(
    onHome: () -> Unit,
    onHistory: () -> Unit,
    onStats: () -> Unit,
    onSettings: () -> Unit,
    onRules: () -> Unit,
    onPostNotification: () -> Unit,
    onStartReceiver: () -> Unit,
    onStopReceiver: () -> Unit,
    onPlayMedia: () -> Unit,
    onStopMedia: () -> Unit,
    playbackStatus: String,
    onOpenShortcuts: () -> Unit,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Review tools", style = MaterialTheme.typography.headlineMedium)
        Text(
            "This mode uses synthetic local content. It never opens a private Telegram account or sends a message."
        )
        Button(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("Open home") }
        Button(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text("Open history") }
        Button(onClick = onStats, modifier = Modifier.fillMaxWidth()) { Text("Open statistics") }
        Button(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Open privacy settings") }
        Button(onClick = onRules, modifier = Modifier.fillMaxWidth()) { Text("Open messaging rules") }
        Button(onClick = onOpenShortcuts, modifier = Modifier.fillMaxWidth()) {
            Text("Open shortcut and block controls")
        }
        Button(onClick = onStartReceiver, modifier = Modifier.fillMaxWidth()) {
            Text("Start foreground receiver demo")
        }
        OutlinedButton(onClick = onStopReceiver, modifier = Modifier.fillMaxWidth()) {
            Text("Stop foreground receiver demo")
        }
        Button(onClick = onPlayMedia, modifier = Modifier.fillMaxWidth()) {
            Text("Play foreground media demo")
        }
        OutlinedButton(onClick = onStopMedia, modifier = Modifier.fillMaxWidth()) {
            Text("Stop media playback")
        }
        Text(
            "Media playback: $playbackStatus",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        Button(onClick = onPostNotification, modifier = Modifier.fillMaxWidth()) {
            Text("Post Android Auto message notification")
        }
        OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
            Text("Exit review demo")
        }
    }
}
