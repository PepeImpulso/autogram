package com.autogram.app.ui.phone

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
            // --- Target (chat + optional topic) ---
            Text("Destino de mensajes", style = MaterialTheme.typography.titleMedium)
            TargetRow(
                hasTarget = state.hasTarget,
                title = state.targetTitle,
                topicName = state.targetTopicName,
                onChange = {
                    val intent = android.content.Intent(context, TargetPickerActivity::class.java)
                    context.startActivity(intent)
                }
            )

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    context.startActivity(android.content.Intent(context, ManagePinnedActivity::class.java))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Gestionar accesos rápidos")
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    context.startActivity(android.content.Intent(context, QuickRepliesSettingsActivity::class.java))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Editar respuestas rápidas")
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

            // Playback speed slider. Range 0.5x–2.0x matches MediaPlayer's usable
            // range for voice (lower gets muddy, higher becomes unintelligible).
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Velocidad de reproducción")
                    Text(
                        "${"%.2f".format(state.playbackSpeed)}x",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = state.playbackSpeed,
                    onValueChange = viewModel::updatePlaybackSpeed,
                    valueRange = 0.5f..2.0f,
                    // 31 steps between 0.5 and 2.0 → 0.05x granularity.
                    steps = 29
                )
            }

            // Start/end recording cue volume. 0 disables the beeps entirely.
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Sonido al grabar")
                    Text(
                        if (state.recordingCuesVolume == 0) "Desactivado"
                        else "${state.recordingCuesVolume}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.recordingCuesVolume == 0)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = state.recordingCuesVolume.toFloat(),
                    onValueChange = { viewModel.updateRecordingCuesVolume(it.toInt()) },
                    valueRange = 0f..100f,
                    // 21 positions: 0, 5, 10, ..., 100 — coarse enough to slide with a thumb in a car.
                    steps = 19
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

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // --- Updates ---
            Text(
                context.getString(com.autogram.app.R.string.settings_updates_section),
                style = MaterialTheme.typography.titleMedium
            )
            UpdatesRow()
        }
    }
}

@Composable
private fun UpdatesRow(viewModel: UpdateViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    val ctx = LocalContext.current
    val state by viewModel.state.collectAsState()
    Text(
        ctx.getString(com.autogram.app.R.string.settings_updates_current_version, viewModel.currentVersion),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    // Status message comes BEFORE the button so the user sees the result of
    // the last check above their next action — feels like a log of what
    // just happened rather than footer text for the button.
    when (val s = state) {
        is UpdateViewModel.State.UpToDate -> {
            Text(
                ctx.getString(com.autogram.app.R.string.settings_updates_up_to_date, s.latest),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
        }
        is UpdateViewModel.State.Error -> {
            Text(
                ctx.getString(com.autogram.app.R.string.settings_updates_error, s.message),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
        }
        is UpdateViewModel.State.Available -> {
            UpdateDialog(
                state = s,
                currentVersion = viewModel.currentVersion,
                onInstall = { viewModel.installNow() },
                onDismiss = { viewModel.dismiss() }
            )
        }
        else -> Unit
    }
    val isBusy = state is UpdateViewModel.State.Checking || state is UpdateViewModel.State.Downloading
    FilledTonalButton(
        onClick = { viewModel.check(userInitiated = true) },
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth()
    ) {
        when (state) {
            is UpdateViewModel.State.Checking -> {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(ctx.getString(com.autogram.app.R.string.settings_updates_checking))
            }
            is UpdateViewModel.State.Downloading -> {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(ctx.getString(com.autogram.app.R.string.update_downloading))
            }
            else -> Text(ctx.getString(com.autogram.app.R.string.settings_updates_check))
        }
    }
}

@Composable
private fun TargetRow(
    hasTarget: Boolean,
    title: String?,
    topicName: String?,
    onChange: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (hasTarget) {
            Text(
                buildString {
                    append(title ?: "(sin título)")
                    if (!topicName.isNullOrBlank()) append(" › ").append(topicName)
                },
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Text(
                "No hay destino configurado. Elige uno para poder enviar y recibir mensajes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Button(onClick = onChange, modifier = Modifier.fillMaxWidth()) {
            Text(if (hasTarget) "Cambiar destino" else "Elegir destino")
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
