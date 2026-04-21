package com.autogram.app.ui.phone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.MessageState
import com.autogram.app.domain.usecase.PlayReplyUseCase
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel, onBack: () -> Unit) {
    val messages by viewModel.messages.collectAsState()
    val playback by viewModel.playbackStatus.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historial") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                }
            )
        }
    ) { padding ->
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("No hay mensajes", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(messages) { message ->
                    MessageCard(
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
fun MessageCard(
    message: Message,
    playback: PlayReplyUseCase.PlaybackStatus,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit
) {
    val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val isOutgoing = message.direction == MessageDirection.OUTGOING
    val hasAudio = message.audioFilePath != null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isOutgoing)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isOutgoing) "Saliente" else "Entrante",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = dateFormat.format(Date(message.createdAt)),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when {
                        message.textContent != null -> message.textContent
                        message.audioFilePath != null -> "🎤 Nota de voz (${message.audioDurationMs?.let { "${it / 1000}s" } ?: "?"})"
                        else -> "Sin contenido"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stateLabel(message.state),
                    style = MaterialTheme.typography.labelSmall,
                    color = stateColor(message.state)
                )
            }
            if (hasAudio && !isOutgoing) {
                PlaybackButton(
                    message = message,
                    playback = playback,
                    onPlay = onPlay,
                    onPause = onPause,
                    onResume = onResume
                )
            }
        }
    }
}

@Composable
private fun PlaybackButton(
    message: Message,
    playback: PlayReplyUseCase.PlaybackStatus,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit
) {
    val isPlayingThis = playback is PlayReplyUseCase.PlaybackStatus.Playing && playback.messageId == message.id
    val isPausedThis = playback is PlayReplyUseCase.PlaybackStatus.Paused && playback.messageId == message.id
    FilledIconButton(
        onClick = when {
            isPlayingThis -> onPause
            isPausedThis -> onResume
            else -> onPlay
        }
    ) {
        Icon(
            imageVector = if (isPlayingThis) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlayingThis) "Pausar" else "Reproducir"
        )
    }
}

private fun stateLabel(state: MessageState): String = when (state) {
    MessageState.IDLE -> "Listo"
    MessageState.RECORDING -> "Grabando..."
    MessageState.RECORDED -> "Grabado"
    MessageState.VALIDATING -> "Validando..."
    MessageState.DISCARDED -> "Descartado"
    MessageState.QUEUED -> "En cola"
    MessageState.QUEUED_OFFLINE -> "En cola (sin conexión)"
    MessageState.SENDING -> "Enviando..."
    MessageState.SENT -> "Enviado ✓"
    MessageState.FAILED -> "Error ✗"
    MessageState.PERMANENTLY_FAILED -> "Error permanente ✗"
    MessageState.RECEIVED -> "Recibido"
    MessageState.PENDING_PLAYBACK -> "Pendiente"
    MessageState.PLAYING -> "Reproduciendo..."
    MessageState.PAUSED -> "Pausado"
    MessageState.PLAYED -> "Reproducido ✓"
    MessageState.ARCHIVED -> "Archivado"
}

@androidx.compose.runtime.Composable
private fun stateColor(state: MessageState) = when (state) {
    MessageState.SENT, MessageState.PLAYED -> MaterialTheme.colorScheme.primary
    MessageState.FAILED, MessageState.PERMANENTLY_FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
