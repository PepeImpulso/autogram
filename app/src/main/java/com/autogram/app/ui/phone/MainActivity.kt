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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.autogram.app.infrastructure.telegram.mtproto.AuthState
import com.autogram.app.infrastructure.telegram.mtproto.TdLibClient
import com.autogram.app.service.MtprotoReceiverService
import com.autogram.app.ui.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var tdLibClient: TdLibClient

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            tdLibClient.ensureStarted()
            val auth = tdLibClient.authState.first { it !is AuthState.Uninitialized && it !is AuthState.Initializing }
            if (auth !is AuthState.Ready) {
                val i = Intent(this@MainActivity, TelegramLoginActivity::class.java)
                i.flags = Intent.FLAG_ACTIVITY_NO_HISTORY
                startActivity(i)
                finish()
                return@launch
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

    val primary = pinned.firstOrNull { it.isActive }
    val secondary = pinned.filter { !it.isActive }

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
            HeroHeader(sendStatus = sendStatus, playback = playback)
            Spacer(Modifier.height(24.dp))

            if (primary != null) {
                RecordCircle(
                    conversation = primary,
                    size = 176.dp,
                    isRecordingThis = activeTargetId == primary.id &&
                        sendStatus is SendVoiceMtprotoUseCase.SendStatus.Recording,
                    pendingCount = pendingCounts[primary.id] ?: 0,
                    hasPermission = hasMicPermission,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onStart = { viewModel.startRecording(primary.id) },
                    onSend = { viewModel.stopAndSend() },
                    onCancel = { viewModel.cancelRecording() }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    primary.targetTitle ?: "Merlin",
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
            } else {
                NoPrimaryState(onConfigure = onManagePinned)
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Mantén pulsado para grabar · suelta para enviar · arrastra fuera para cancelar",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.height(24.dp))

            SecondaryCirclesRow(
                circles = secondary,
                activeTargetId = activeTargetId,
                sendStatus = sendStatus,
                pendingCounts = pendingCounts,
                hasPermission = hasMicPermission,
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onStart = { viewModel.startRecording(it.id) },
                onSend = { viewModel.stopAndSend() },
                onCancel = { viewModel.cancelRecording() },
                onManage = onManagePinned
            )

            Spacer(Modifier.weight(1f))
            QuickReplies(
                items = quickReplies,
                onSend = { viewModel.sendQuickReply(it) },
                onEdit = onManageQuickReplies
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
private fun HeroHeader(
    sendStatus: SendVoiceMtprotoUseCase.SendStatus,
    playback: PlayReplyUseCase.PlaybackStatus
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
            "Autogram",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        StatusPill(sendStatus, playback)
    }
}

@Composable
private fun StatusPill(
    sendStatus: SendVoiceMtprotoUseCase.SendStatus,
    playback: PlayReplyUseCase.PlaybackStatus
) {
    data class Pill(val text: String, val container: Color, val onContainer: Color)
    val scheme = MaterialTheme.colorScheme
    val pill = when (sendStatus) {
        is SendVoiceMtprotoUseCase.SendStatus.Recording ->
            Pill("Grabando…", scheme.errorContainer, scheme.onErrorContainer)
        is SendVoiceMtprotoUseCase.SendStatus.Validating ->
            Pill("Validando…", scheme.tertiaryContainer, scheme.onTertiaryContainer)
        is SendVoiceMtprotoUseCase.SendStatus.Sending ->
            Pill("Enviando…", scheme.primaryContainer, scheme.onPrimaryContainer)
        is SendVoiceMtprotoUseCase.SendStatus.Sent ->
            Pill("Enviado ✓", scheme.primaryContainer, scheme.onPrimaryContainer)
        is SendVoiceMtprotoUseCase.SendStatus.Discarded ->
            Pill("Descartado: ${sendStatus.reason}", scheme.tertiaryContainer, scheme.onTertiaryContainer)
        is SendVoiceMtprotoUseCase.SendStatus.Error ->
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
private fun NoPrimaryState(onConfigure: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 32.dp)
    ) {
        Text(
            "No hay destino principal configurado.",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onConfigure) {
            Text("Configurar accesos rápidos")
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
                    contentDescription = "Cancelar",
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
                contentDescription = "Grabar",
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
                            contentDescription = "Gestionar accesos rápidos",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Añadir",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickReplies(
    items: List<Pair<String, String>>,
    onSend: (String) -> Unit,
    onEdit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Respuestas rápidas",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Editar respuestas rápidas",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
