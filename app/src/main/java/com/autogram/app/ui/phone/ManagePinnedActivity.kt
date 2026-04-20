package com.autogram.app.ui.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autogram.app.domain.model.Conversation
import com.autogram.app.ui.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Pantalla "Gestionar accesos rápidos": lista los círculos fijados a la pantalla
 * principal, permite añadir nuevos (via [PinnedPickerActivity]), elegir cuál es
 * el primario (Merlin — círculo grande), establecer una imagen de fondo y
 * eliminar círculos secundarios.
 */
@AndroidEntryPoint
class ManagePinnedActivity : ComponentActivity() {

    private val viewModel: ManagePinnedViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutogramTheme {
                ManagePinnedScreen(
                    viewModel = viewModel,
                    onBack = { finish() },
                    onAdd = { startActivity(Intent(this, PinnedPickerActivity::class.java)) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManagePinnedScreen(
    viewModel: ManagePinnedViewModel,
    onBack: () -> Unit,
    onAdd: () -> Unit
) {
    val pinned by viewModel.pinned.collectAsState()

    // Target we are about to set a photo for (lifted out of the list item so
    // the photo picker's callback can still reach it after the item recomposes).
    // Uses the system Photo Picker (PickVisualMedia) — handles back-press
    // cleanly and doesn't require storage permissions.
    var photoTargetId by remember { mutableStateOf<String?>(null) }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val id = photoTargetId
        photoTargetId = null
        if (uri != null && id != null) {
            viewModel.setPhoto(id, uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accesos rápidos") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Añadir") }
            )
        }
    ) { padding ->
        if (pinned.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "Aún no hay accesos rápidos. Añade el bot principal o un topic de un grupo foro.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(pinned, key = { it.id }) { conv ->
                PinnedRow(
                    conv = conv,
                    onSetPrimary = { viewModel.setPrimary(conv.id) },
                    onPickPhoto = {
                        photoTargetId = conv.id
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onClearPhoto = { viewModel.clearPhoto(conv.id) },
                    onRemove = { viewModel.remove(conv.id) }
                )
            }
        }
    }
}

@Composable
private fun PinnedRow(
    conv: Conversation,
    onSetPrimary: () -> Unit,
    onPickPhoto: () -> Unit,
    onClearPhoto: () -> Unit,
    onRemove: () -> Unit
) {
    ListItem(
        leadingContent = {
            CircleAvatar(conv = conv, size = 48.dp)
        },
        headlineContent = {
            Text(
                buildString {
                    append(conv.targetTitle ?: "(sin título)")
                    conv.topicName?.let { append(" › ").append(it) }
                }
            )
        },
        supportingContent = {
            Text(
                if (conv.isActive) "Principal (Merlin)"
                else "Acceso rápido",
                style = MaterialTheme.typography.bodySmall,
                color = if (conv.isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onSetPrimary) {
                    Icon(
                        imageVector = if (conv.isActive) Icons.Filled.Star
                                      else Icons.Outlined.StarBorder,
                        contentDescription = "Marcar como principal",
                        tint = if (conv.isActive) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = if (conv.photoLocalPath != null) onClearPhoto else onPickPhoto
                ) {
                    Icon(Icons.Filled.Image, "Imagen de fondo")
                }
                // Allow removing only non-primary circles — the primary target
                // is managed through Settings › Destino de mensajes.
                if (!conv.isActive) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Filled.Delete, "Quitar")
                    }
                }
            }
        }
    )
}
