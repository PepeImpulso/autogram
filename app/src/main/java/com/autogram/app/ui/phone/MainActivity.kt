package com.autogram.app.ui.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.domain.usecase.PlayReplyUseCase
import com.autogram.app.domain.usecase.SendMessageUseCase
import com.autogram.app.service.AutogramPollingService
import com.autogram.app.ui.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Pantalla principal del teléfono. Si no hay conversación configurada,
 * redirige a OnboardingActivity. Si la hay, arranca el servicio de polling
 * y muestra una UI estilo "push to talk" para grabar y enviar notas de voz
 * sin necesidad de Android Auto.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var conversationRepository: ConversationRepository

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
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
                        onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                        onHistory = { startActivity(Intent(this, HistoryActivity::class.java)) },
                        onStats = { startActivity(Intent(this, StatsActivity::class.java)) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onStats: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val ctx = LocalContext.current
    val sendStatus by viewModel.sendStatus.collectAsState()
    val playback by viewModel.playbackStatus.collectAsState()
    val quickReplies by viewModel.quickReplies.collectAsState()

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
        topBar = {
            TopAppBar(
                title = { Text("Autogram") },
                actions = {
                    TextButton(onClick = onHistory) { Text("Historial") }
                    TextButton(onClick = onStats) { Text("Stats") }
                    TextButton(onClick = onSettings) { Text("Ajustes") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusPill(sendStatus, playback)

            Spacer(Modifier.weight(1f))

            RecordButton(
                status = sendStatus,
                hasPermission = hasMicPermission,
                onRequestPermission = {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onStart = { viewModel.startRecording() },
                onSend = { viewModel.stopAndSend() },
                onCancel = { viewModel.cancelRecording() }
            )

            Spacer(Modifier.height(16.dp))
            Text(
                "Mantén pulsado para grabar. Suelta para enviar.\nDesliza fuera del botón para cancelar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.weight(1f))

            Text("Respuestas rápidas", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            quickReplies.forEach { (label, text) ->
                OutlinedButton(
                    onClick = { viewModel.sendQuickReply(text) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text(label)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatusPill(
    sendStatus: SendMessageUseCase.SendStatus,
    playback: PlayReplyUseCase.PlaybackStatus
) {
    val (text, color) = when (sendStatus) {
        is SendMessageUseCase.SendStatus.Recording -> "🎤 Grabando…" to MaterialTheme.colorScheme.error
        is SendMessageUseCase.SendStatus.Validating -> "Validando…" to MaterialTheme.colorScheme.tertiary
        is SendMessageUseCase.SendStatus.Sending -> "Enviando…" to MaterialTheme.colorScheme.primary
        is SendMessageUseCase.SendStatus.Sent -> "Enviado ✓" to MaterialTheme.colorScheme.primary
        is SendMessageUseCase.SendStatus.Discarded -> "Descartado: ${sendStatus.reason}" to MaterialTheme.colorScheme.tertiary
        is SendMessageUseCase.SendStatus.Error -> "Error: ${sendStatus.message}" to MaterialTheme.colorScheme.error
        else -> {
            val pendingCount = (playback as? PlayReplyUseCase.PlaybackStatus.PendingCount)?.count ?: 0
            if (pendingCount > 0) "$pendingCount mensajes pendientes" to MaterialTheme.colorScheme.tertiary
            else "Listo" to MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
    val containerColor =
        if (isRecording) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .size(160.dp)
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
            shadowElevation = if (isRecording) 12.dp else 6.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (isRecording) "■" else "🎤",
                    color = Color.White,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
    if (!hasPermission) {
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRequestPermission) {
            Text("Conceder permiso de micrófono")
        }
    }
}
