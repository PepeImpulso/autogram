package com.autogram.app.interface.phone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.MessageState
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel, onBack: () -> Unit) {
    val messages by viewModel.messages.collectAsState()

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
                    MessageCard(message)
                }
            }
        }
    }
}

@Composable
fun MessageCard(message: Message) {
    val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val isOutgoing = message.direction == MessageDirection.OUTGOING

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isOutgoing)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
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
                    message.audioFilePath != null -> "🎤 Nota de voz (${message.audioDurationMs?.let { "${it / 1000}s" } ?: "?")"
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
    }
}

private fun stateLabel(state: MessageState): String = when (state) {
    MessageState.IDLE -> "Listo"
    MessageState.RECORDING -> "Grabando..."
    MessageState.RECORDED -> "Grabado"
    MessageState.VALIDATING -> "Validando..."
    MessageState.DISCARDED -> "Descartado"
    MessageState.QUEUED -> "En cola"
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

private fun stateColor(state: MessageState) = when (state) {
    MessageState.SENT, MessageState.PLAYED -> MaterialTheme.colorScheme.primary
    MessageState.FAILED, MessageState.PERMANENTLY_FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
