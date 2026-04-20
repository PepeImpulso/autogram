package com.autogram.app.ui.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autogram.app.domain.model.QuickReply
import com.autogram.app.domain.repository.QuickReplyRepository
import com.autogram.app.ui.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class QuickRepliesSettingsActivity : ComponentActivity() {

    private val viewModel: QuickRepliesSettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutogramTheme {
                QuickRepliesScreen(viewModel = viewModel, onBack = { finish() })
            }
        }
    }
}

@HiltViewModel
class QuickRepliesSettingsViewModel @Inject constructor(
    private val repo: QuickReplyRepository
) : ViewModel() {

    val replies: StateFlow<List<QuickReply>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun save(existing: QuickReply?, label: String, text: String) {
        if (label.isBlank() || text.isBlank()) return
        viewModelScope.launch {
            val all = repo.getAll()
            val position = existing?.position ?: ((all.maxOfOrNull { it.position } ?: 0) + 1)
            repo.upsert(
                QuickReply(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    label = label.trim(),
                    textContent = text.trim(),
                    position = position,
                    isEnabled = existing?.isEnabled ?: true
                )
            )
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.delete(id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickRepliesScreen(
    viewModel: QuickRepliesSettingsViewModel,
    onBack: () -> Unit
) {
    val replies by viewModel.replies.collectAsState()
    var editing by remember { mutableStateOf<QuickReply?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Respuestas rápidas") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = null; showEditor = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Nueva") }
            )
        }
    ) { padding ->
        if (replies.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Crea tus propias respuestas rápidas para enviar con un toque desde la pantalla principal.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(replies, key = { it.id }) { r ->
                    ListItem(
                        headlineContent = { Text(r.label) },
                        supportingContent = {
                            Text(r.textContent, style = MaterialTheme.typography.bodySmall)
                        },
                        trailingContent = {
                            IconButton(onClick = { viewModel.delete(r.id) }) {
                                Icon(Icons.Filled.Delete, "Eliminar")
                            }
                        },
                        modifier = Modifier.clickable(onClick = {
                            editing = r
                            showEditor = true
                        })
                    )
                }
            }
        }
    }

    if (showEditor) {
        QuickReplyEditor(
            initial = editing,
            onSave = { label, text ->
                viewModel.save(editing, label, text)
                showEditor = false
                editing = null
            },
            onDismiss = { showEditor = false; editing = null }
        )
    }
}

@Composable
private fun QuickReplyEditor(
    initial: QuickReply?,
    onSave: (label: String, text: String) -> Unit,
    onDismiss: () -> Unit
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var text by remember { mutableStateOf(initial?.textContent ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nueva respuesta" else "Editar respuesta") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Etiqueta (chip)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Texto que se envía") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(label, text) },
                enabled = label.isNotBlank() && text.isNotBlank()
            ) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

