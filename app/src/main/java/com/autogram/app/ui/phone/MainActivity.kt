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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import com.autogram.app.BuildConfig
import com.autogram.app.R
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.domain.usecase.SendMessageUseCase
import com.autogram.app.infrastructure.telegram.mtproto.AuthState
import com.autogram.app.infrastructure.telegram.mtproto.TdLibClient
import com.autogram.app.service.AutogramPollingService
import com.autogram.app.ui.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var conversationRepository: ConversationRepository
    @Inject lateinit var tdLibClient: TdLibClient

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            // 1) MTProto session gate — must be logged in to @pepebot's owner
            //    account before anything else. Skipping this means the bot-API
            //    send/receive paths wouldn't appear as "user → bot".
            tdLibClient.ensureStarted()
            val auth = tdLibClient.authState.first { it !is AuthState.Uninitialized && it !is AuthState.Initializing }
            if (auth !is AuthState.Ready) {
                val i = Intent(this@MainActivity, TelegramLoginActivity::class.java)
                i.flags = Intent.FLAG_ACTIVITY_NO_HISTORY
                startActivity(i)
                finish()
                return@launch
            }

            // 2) Legacy bot-API gate (kept until Phase 4 cleanup)
            val active = conversationRepository.getActive()
            if (active == null || active.botToken.isBlank() || active.targetChatId.isBlank()) {
                val i = Intent(this@MainActivity, OnboardingActivity::class.java)
                i.flags = Intent.FLAG_ACTIVITY_NO_HISTORY
                startActivity(i)
                finish()
                return@launch
            }
            startPollingService()
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
                        onOpenStats = { startActivity(Intent(this, StatsActivity::class.java)) }
                    )
                }
            }
        }
    }

    private fun startPollingService() {
        val serviceIntent = Intent(this, AutogramPollingService::class.java).apply {
            action = AutogramPollingService.ACTION_START
        }
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Error starting foreground service")
        }
    }
}

@Composable
private fun MainScreen(
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenStats: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel()
) {
    val ctx = LocalContext.current
    val sendStatus by viewModel.sendStatus.collectAsState()
    val playback by viewModel.playbackStatus.collectAsState()
    val quickReplies by viewModel.quickReplies.collectAsState()
    val updateState by updateViewModel.state.collectAsState()

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
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMicPermission = granted }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Inicio") }
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
            HeroHeader()
            Spacer(Modifier.height(4.dp))
            StatusPill(sendStatus, playback)
            Spacer(Modifier.weight(1f))
            RecordButton(
                status = sendStatus,
                hasPermission = hasMicPermission,
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onStart = { viewModel.startRecording() },
                onSend = { viewModel.stopAndSend() },
                onCancel = { viewModel.cancelRecording() }
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Mantén pulsado para grabar · suelta para enviar · arrastra fuera para cancelar",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.weight(1f))
            QuickReplies(
                items = quickReplies,
                onSend = { viewModel.sendQuickReply(it) }
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    // Update dialog overlay
    when (val s = updateState) {
        is UpdateViewModel.State.Available -> {
            UpdateDialog(
                state = s,
                currentVersion = updateViewModel.currentVersion,
                onInstall = { updateViewModel.installNow() },
                onDismiss = { updateViewModel.dismiss() }
            )
        }
        else -> Unit
    }
}

@Composable
private fun HeroHeader() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.autogram_logo),
            contentDescription = null,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Autogram",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusPill(
    sendStatus: SendMessageUseCase.SendStatus,
    playback: PlayReplyUseCase.PlaybackStatus
) {
    data class Pill(val text: String, val container: Color, val onContainer: Color)
    val scheme = MaterialTheme.colorScheme
    val pill = when (sendStatus) {
        is SendMessageUseCase.SendStatus.Recording ->
            Pill("Grabando…", scheme.errorContainer, scheme.onErrorContainer)
        is SendMessageUseCase.SendStatus.Validating ->
            Pill("Validando…", scheme.tertiaryContainer, scheme.onTertiaryContainer)
        is SendMessageUseCase.SendStatus.Sending ->
            Pill("Enviando…", scheme.primaryContainer, scheme.onPrimaryContainer)
        is SendMessageUseCase.SendStatus.Sent ->
            Pill("Enviado ✓", scheme.primaryContainer, scheme.onPrimaryContainer)
        is SendMessageUseCase.SendStatus.Discarded ->
            Pill("Descartado: ${sendStatus.reason}", scheme.tertiaryContainer, scheme.onTertiaryContainer)
        is SendMessageUseCase.SendStatus.Error ->
            Pill("Error: ${sendStatus.message}", scheme.errorContainer, scheme.onErrorContainer)
        else -> {
            val pendingCount = (playback as? PlayReplyUseCase.PlaybackStatus.PendingCount)?.count ?: 0
            if (pendingCount > 0)
                Pill("$pendingCount mensajes pendientes", scheme.tertiaryContainer, scheme.onTertiaryContainer)
            else
                Pill("Listo", scheme.surfaceVariant, scheme.onSurfaceVariant)
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

@Composable
private fun RecordButton(
    status: SendMessageUseCase.SendStatus,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit
) {
    val isRecording = status is SendMessageUseCase.SendStatus.Recording
    val scale by animateFloatAsState(
        targetValue = if (isRecording) 1.1f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "mic-scale"
    )
    val containerColor =
        if (isRecording) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary
    val pulseColor = containerColor.copy(alpha = if (isRecording) 0.15f else 0f)

    Box(contentAlignment = Alignment.Center) {
        // Pulse halo when recording
        Box(
            modifier = Modifier
                .size(200.dp)
                .scale(scale * 1.15f)
                .clip(CircleShape)
                .background(pulseColor)
        )
        Box(
            modifier = Modifier
                .size(176.dp)
                .scale(scale)
                .clip(CircleShape)
                .pointerInput(hasPermission) {
                    if (!hasPermission) return@pointerInput
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
                                pos.x > size.width || pos.y > size.height
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
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                color = containerColor,
                shadowElevation = if (isRecording) 14.dp else 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isRecording) Icons.Filled.Close else Icons.Filled.Mic,
                        contentDescription = if (isRecording) "Cancelar" else "Grabar",
                        tint = Color.White,
                        modifier = Modifier.size(72.dp)
                    )
                }
            }
        }
    }
    if (!hasPermission) {
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onRequestPermission) {
            Icon(Icons.Filled.Mic, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Conceder permiso de micrófono")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickReplies(
    items: List<Pair<String, String>>,
    onSend: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Respuestas rápidas",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.Center) {
            items.forEach { (label, text) ->
                AssistChip(
                    onClick = { onSend(text) },
                    label = { Text(label) },
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}
