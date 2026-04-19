package com.autogram.app.interface.phone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pantalla de configuración con Compose.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuración") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // --- Telegram Configuration ---
            Text("Telegram Bot", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = state.botToken,
                onValueChange = viewModel::updateBotToken,
                label = { Text("Bot Token") },
                placeholder = { Text("123456789:ABCdef...") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                // BUG-39: Validate token format
                isError = state.botToken.isNotBlank() && !state.botToken.matches(Regex("\\d+:[A-Za-z0-9_-]{30,}")),
                supportingText = {
                    if (state.botToken.isNotBlank() && !state.botToken.matches(Regex("\\d+:[A-Za-z0-9_-]{30,}"))) {
                        Text("Formato inválido. Esperado: número:hash")
                    }
                }
            )

            OutlinedTextField(
                value = state.chatId,
                onValueChange = viewModel::updateChatId,
                label = { Text("Chat ID") },
                placeholder = { Text("-1001234567890") },
                // BUG-28: Validate chatId is numeric
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = state.chatId.isNotBlank() && state.chatId.toLongOrNull() == null,
                supportingText = {
                    if (state.chatId.isNotBlank() && state.chatId.toLongOrNull() == null) {
                        Text("El Chat ID debe ser numérico")
                    }
                }
            )

            OutlinedTextField(
                value = state.chatTitle,
                onValueChange = viewModel::updateChatTitle,
                label = { Text("Nombre del chat (opcional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = viewModel::testConnection,
                    enabled = state.botToken.isNotBlank() && !state.isTesting,
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Probar conexión")
                    }
                }

                Button(
                    onClick = viewModel::saveSettings,
                    enabled = state.botToken.isNotBlank() && state.chatId.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Guardar")
                }
            }

            // Resultado de prueba/guardado
            state.testResult?.let { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (result.contains("Error"))
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        text = result,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // --- Playback Settings ---
            Text("Reproducción", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Auto-reproducir respuestas")
                Switch(
                    checked = state.autoPlay,
                    onCheckedChange = viewModel::updateAutoPlay
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Modo silencio")
                Switch(
                    checked = state.silentMode,
                    onCheckedChange = viewModel::updateSilentMode
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // --- Advanced Settings ---
            Text("Avanzado", style = MaterialTheme.typography.titleMedium)

            var retentionText by remember(state.retentionDays) {
                mutableStateOf(state.retentionDays.toString())
            }
            // BUG-48 FIX: Debounce retention days updates
            var retentionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
            OutlinedTextField(
                value = retentionText,
                onValueChange = {
                    retentionText = it
                    retentionJob?.cancel()
                    retentionJob = coroutineScope.launch {
                        kotlinx.coroutines.delay(800)
                        it.toIntOrNull()?.let { days -> viewModel.updateRetentionDays(days) }
                    }
                },
                label = { Text("Retención de historial (días)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Text(
                "Duración mínima de grabación: ${state.minDuration}ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Reintentos máximos de envío: ${state.maxRetries}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // --- Battery optimization opt-out ---
            Text(
                context.getString(com.autogram.app.R.string.settings_battery_section),
                style = MaterialTheme.typography.titleMedium
            )
            BatteryOptimizationRow(context)
        }
    }
}

@Composable
private fun BatteryOptimizationRow(context: android.content.Context) {
    val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    var isIgnoring by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    DisposableEffect(Unit) {
        // Refresh state on resume — user may grant/revoke from system settings.
        val lifecycleOwner = (context as? androidx.lifecycle.LifecycleOwner)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    Button(
        onClick = {
            if (!isIgnoring) {
                @Suppress("BatteryLife")
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                ).setData(android.net.Uri.parse("package:${context.packageName}"))
                try {
                    context.startActivity(intent)
                } catch (_: android.content.ActivityNotFoundException) {
                    val fallback = android.content.Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                    )
                    context.startActivity(fallback)
                }
            }
        },
        enabled = !isIgnoring,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            context.getString(
                if (isIgnoring) com.autogram.app.R.string.settings_battery_button_already
                else com.autogram.app.R.string.settings_battery_button_request
            )
        )
    }
}
