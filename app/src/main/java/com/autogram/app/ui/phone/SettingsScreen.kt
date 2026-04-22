package com.autogram.app.ui.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.autogram.app.R
import kotlinx.coroutines.launch

/**
 * Configuración — visual premium: tarjetas de sección con icono cabecero, filas
 * con estilo ListItem y separadores finos, paleta de surfaces de Material 3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Export / import plumbing — driven from BackupCard below, hoisted here so
    // the ActivityResult launchers and pending-bytes buffer live across the
    // section card recompositions.
    var pendingExportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingImportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var showImportPasswordDialog by remember { mutableStateOf(false) }
    var showImportConfirmDialog by remember { mutableStateOf(false) }

    val createDocumentLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val bytes = pendingExportBytes
        pendingExportBytes = null
        if (uri != null && bytes != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error("openOutputStream returned null")
            }.onSuccess {
                android.widget.Toast.makeText(context, context.getString(R.string.backup_export_ok), android.widget.Toast.LENGTH_LONG).show()
            }.onFailure { e ->
                android.widget.Toast.makeText(context, context.getString(R.string.backup_export_fail, e.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    val openDocumentLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("openInputStream returned null")
            }.onSuccess { bytes ->
                pendingImportBytes = bytes
                showImportConfirmDialog = true
            }.onFailure { e ->
                android.widget.Toast.makeText(context, context.getString(R.string.backup_import_fail, e.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // Observe backup events to wire export bytes → SAF + show import toasts.
    LaunchedEffect(Unit) {
        viewModel.backupEvents.collect { event ->
            when (event) {
                is SettingsViewModel.BackupEvent.ExportReady -> {
                    pendingExportBytes = event.bytes
                    val fname = "autogram-backup-${java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())}.autogram"
                    createDocumentLauncher.launch(fname)
                }
                is SettingsViewModel.BackupEvent.ExportFailed ->
                    android.widget.Toast.makeText(context, context.getString(R.string.backup_export_fail, event.message), android.widget.Toast.LENGTH_LONG).show()
                is SettingsViewModel.BackupEvent.ImportSuccess -> {
                    // Apply restored language globally so every activity (Main,
                    // History, ManagePinned) picks it up on next resume.
                    val locales = if (event.languageTag.isBlank()) androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                                  else androidx.core.os.LocaleListCompat.forLanguageTags(event.languageTag)
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
                    android.widget.Toast.makeText(context, context.getString(R.string.backup_import_ok), android.widget.Toast.LENGTH_LONG).show()
                    // Recreate so the Settings screen itself reflects the
                    // restored settings + language without going back to Main.
                    (context as? android.app.Activity)?.recreate()
                }
                SettingsViewModel.BackupEvent.ImportWrongPassword ->
                    android.widget.Toast.makeText(context, context.getString(R.string.backup_import_wrong_password), android.widget.Toast.LENGTH_LONG).show()
                SettingsViewModel.BackupEvent.ImportCorrupt ->
                    android.widget.Toast.makeText(context, context.getString(R.string.backup_import_corrupt), android.widget.Toast.LENGTH_LONG).show()
                is SettingsViewModel.BackupEvent.ImportFailed ->
                    android.widget.Toast.makeText(context, context.getString(R.string.backup_import_fail, event.message), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    if (showExportPasswordDialog) {
        ExportPasswordDialog(
            onDismiss = { showExportPasswordDialog = false },
            onConfirm = { pwd ->
                showExportPasswordDialog = false
                viewModel.requestExport(pwd)
            }
        )
    }

    if (showImportConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showImportConfirmDialog = false; pendingImportBytes = null },
            title = { Text(stringResource(R.string.backup_import_confirm_title)) },
            text = { Text(stringResource(R.string.backup_import_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showImportConfirmDialog = false
                    showImportPasswordDialog = true
                }) { Text(stringResource(R.string.backup_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportConfirmDialog = false
                    pendingImportBytes = null
                }) { Text(stringResource(R.string.backup_cancel)) }
            }
        )
    }

    if (showImportPasswordDialog) {
        ImportPasswordDialog(
            onDismiss = { showImportPasswordDialog = false; pendingImportBytes = null },
            onConfirm = { pwd ->
                val bytes = pendingImportBytes
                pendingImportBytes = null
                showImportPasswordDialog = false
                if (bytes != null) viewModel.requestImport(bytes, pwd)
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionCard(
                icon = Icons.Filled.ChatBubble,
                title = stringResource(R.string.settings_section_destination),
                subtitle = stringResource(R.string.settings_section_destination_sub)
            ) {
                TargetSummary(
                    title = state.targetTitle,
                    topicName = state.targetTopicName,
                    hasTarget = state.hasTarget
                )
                SettingsDivider()
                NavigationRow(
                    label = if (state.hasTarget) stringResource(R.string.settings_change_target)
                            else stringResource(R.string.settings_choose_target),
                    onClick = {
                        context.startActivity(android.content.Intent(context, TargetPickerActivity::class.java))
                    }
                )
                SettingsDivider()
                NavigationRow(
                    label = stringResource(R.string.settings_manage_pinned),
                    supporting = stringResource(R.string.settings_manage_pinned_sub),
                    onClick = {
                        context.startActivity(android.content.Intent(context, ManagePinnedActivity::class.java))
                    }
                )
            }

            SectionCard(
                icon = Icons.Filled.PlayCircle,
                title = stringResource(R.string.settings_section_playback),
                subtitle = stringResource(R.string.settings_section_playback_sub)
            ) {
                SwitchRow(
                    label = stringResource(R.string.settings_autoplay),
                    checked = state.autoPlay,
                    onCheckedChange = viewModel::updateAutoPlay
                )
                SettingsDivider()
                SwitchRow(
                    label = stringResource(R.string.settings_silent),
                    supporting = stringResource(R.string.settings_silent_sub),
                    checked = state.silentMode,
                    onCheckedChange = viewModel::updateSilentMode
                )
                SettingsDivider()
                SliderRow(
                    label = stringResource(R.string.settings_playback_speed),
                    value = state.playbackSpeed,
                    valueLabel = "${"%.2f".format(state.playbackSpeed)}x",
                    onValueChange = viewModel::updatePlaybackSpeed,
                    valueRange = 0.5f..2.0f,
                    steps = 29
                )
                SettingsDivider()
                SliderRow(
                    label = stringResource(R.string.settings_recording_cues),
                    value = state.recordingCuesVolume.toFloat(),
                    valueLabel = if (state.recordingCuesVolume == 0) stringResource(R.string.settings_recording_cues_off)
                                 else "${state.recordingCuesVolume}%",
                    valueLabelDim = state.recordingCuesVolume == 0,
                    onValueChange = { viewModel.updateRecordingCuesVolume(it.toInt()) },
                    valueRange = 0f..100f,
                    steps = 19
                )
                SettingsDivider()
                SwitchRow(
                    label = stringResource(R.string.settings_auto_delete_played),
                    supporting = stringResource(R.string.settings_auto_delete_played_sub),
                    checked = state.autoDeletePlayed,
                    onCheckedChange = viewModel::updateAutoDeletePlayed
                )
                SettingsDivider()
                SwitchRow(
                    label = stringResource(R.string.settings_haptic),
                    supporting = stringResource(R.string.settings_haptic_sub),
                    checked = state.hapticEnabled,
                    onCheckedChange = viewModel::updateHaptic
                )
                SettingsDivider()
                SwitchRow(
                    label = stringResource(R.string.settings_preview_before_send),
                    supporting = stringResource(R.string.settings_preview_before_send_sub),
                    checked = state.previewBeforeSend,
                    onCheckedChange = viewModel::updatePreviewBeforeSend
                )
            }

            SectionCard(
                icon = Icons.Filled.Fingerprint,
                title = stringResource(R.string.settings_section_security),
                subtitle = stringResource(R.string.settings_section_security_sub)
            ) {
                SwitchRow(
                    label = stringResource(R.string.settings_biometric_lock),
                    supporting = stringResource(R.string.settings_biometric_lock_sub),
                    checked = state.biometricLockEnabled,
                    onCheckedChange = viewModel::updateBiometricLock
                )
            }

            SectionCard(
                icon = Icons.Filled.Language,
                title = stringResource(R.string.settings_section_language),
                subtitle = stringResource(R.string.settings_section_language_sub)
            ) {
                LanguageSelector(
                    currentTag = state.languageTag,
                    onSelect = { tag ->
                        viewModel.updateLanguage(tag)
                        // Force the current activity to redraw with the new locale;
                        // BaseLocaleActivity.attachBaseContext reads from LocaleHelper
                        // prefs on the rebuilt instance.
                        (context as? android.app.Activity)?.recreate()
                    }
                )
            }

            SectionCard(
                icon = Icons.Filled.History,
                title = stringResource(R.string.settings_section_history),
                subtitle = stringResource(R.string.settings_section_history_sub)
            ) {
                RetentionField(
                    initialDays = state.retentionDays,
                    onCommit = viewModel::updateRetentionDays
                )
                SettingsDivider()
                ValueRow(
                    label = stringResource(R.string.settings_min_duration),
                    value = stringResource(R.string.settings_min_duration_value, state.minDuration.toInt())
                )
                SettingsDivider()
                ValueRow(
                    label = stringResource(R.string.settings_max_retries),
                    value = "${state.maxRetries}"
                )
            }

            SectionCard(
                icon = Icons.Filled.Backup,
                title = stringResource(R.string.settings_section_backup),
                subtitle = stringResource(R.string.settings_section_backup_sub)
            ) {
                NavigationRow(
                    label = stringResource(R.string.settings_backup_export),
                    supporting = stringResource(R.string.settings_backup_export_sub),
                    onClick = { showExportPasswordDialog = true }
                )
                SettingsDivider()
                NavigationRow(
                    label = stringResource(R.string.settings_backup_import),
                    supporting = stringResource(R.string.settings_backup_import_sub),
                    onClick = { openDocumentLauncher.launch(arrayOf("*/*")) }
                )
            }

            SectionCard(
                icon = Icons.Filled.Battery4Bar,
                title = stringResource(R.string.settings_battery_section)
            ) {
                BatteryOptimizationRow(context)
            }

            SectionCard(
                icon = Icons.Filled.SystemUpdate,
                title = stringResource(R.string.settings_updates_section)
            ) {
                UpdatesRow()
            }

            // Only expose the Sentry-test affordance when a DSN is actually
            // baked in — users on dev builds without Sentry don't need it.
            if (com.autogram.app.BuildConfig.SENTRY_DSN.isNotBlank()) {
                SectionCard(
                    icon = Icons.Filled.BugReport,
                    title = stringResource(R.string.settings_diagnostics_section)
                ) {
                    SentryTestRow()
                }
            }
        }
    }
}

@Composable
private fun SentryTestRow() {
    val ctx = LocalContext.current
    var sentAtMs by remember { mutableStateOf(0L) }
    val justSent = sentAtMs != 0L && System.currentTimeMillis() - sentAtMs < 3000
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    io.sentry.Sentry.captureException(
                        RuntimeException("Test event from Ajustes → Diagnóstico")
                    )
                    sentAtMs = System.currentTimeMillis()
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_diagnostics_test_sentry),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    if (justSent) stringResource(R.string.settings_diagnostics_test_sent)
                    else stringResource(R.string.settings_diagnostics_test_sentry_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (justSent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// --- Layout primitives ----------------------------------------------------

@Composable
private fun SectionCard(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
            content()
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    )
}

@Composable
private fun TargetSummary(title: String?, topicName: String?, hasTarget: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (hasTarget) {
            Text(
                title ?: stringResource(R.string.common_untitled),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            if (!topicName.isNullOrBlank()) {
                Text(
                    topicName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Text(
                stringResource(R.string.settings_no_target),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NavigationRow(
    label: String,
    supporting: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supporting: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabelDim: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = if (valueLabelDim) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps
        )
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RetentionField(initialDays: Int, onCommit: (Int) -> Unit) {
    var text by remember(initialDays) { mutableStateOf(initialDays.toString()) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            job?.cancel()
            job = scope.launch {
                kotlinx.coroutines.delay(800)
                it.toIntOrNull()?.let(onCommit)
            }
        },
        label = { Text(stringResource(R.string.settings_retention)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun LanguageSelector(currentTag: String, onSelect: (String) -> Unit) {
    // Each entry is (BCP-47 tag, label). Empty tag = follow system default.
    val options = listOf(
        "" to stringResource(R.string.settings_language_system),
        "es" to stringResource(R.string.settings_language_es),
        "en" to stringResource(R.string.settings_language_en),
        "fr" to stringResource(R.string.settings_language_fr),
        "de" to stringResource(R.string.settings_language_de),
        "zh" to stringResource(R.string.settings_language_zh),
        "ja" to stringResource(R.string.settings_language_ja)
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (tag, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(tag) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = tag == currentTag,
                    onClick = { onSelect(tag) }
                )
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
            if (i < options.lastIndex) SettingsDivider()
        }
    }
}

@Composable
private fun UpdatesRow(viewModel: UpdateViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    val ctx = LocalContext.current
    val state by viewModel.state.collectAsState()
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            ctx.getString(R.string.settings_updates_current_version, viewModel.currentVersion),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        when (val s = state) {
            is UpdateViewModel.State.UpToDate -> {
                Text(
                    ctx.getString(R.string.settings_updates_up_to_date, s.latest),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
            }
            is UpdateViewModel.State.Error -> {
                Text(
                    ctx.getString(R.string.settings_updates_error, s.message),
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
                    Text(ctx.getString(R.string.settings_updates_checking))
                }
                is UpdateViewModel.State.Downloading -> {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(ctx.getString(R.string.update_downloading))
                }
                else -> Text(ctx.getString(R.string.settings_updates_check))
            }
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
        val lifecycleOwner = (context as? androidx.lifecycle.LifecycleOwner)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        FilledTonalButton(
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
                    if (isIgnoring) R.string.settings_battery_button_already
                    else R.string.settings_battery_button_request
                )
            )
        }
    }
}

@Composable
private fun ExportPasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit
) {
    var pwd by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val emptyMsg = stringResource(R.string.backup_password_empty)
    val mismatchMsg = stringResource(R.string.backup_password_mismatch)
    val tooShortMsg = stringResource(R.string.backup_password_too_short)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_password_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it; error = null },
                    label = { Text(stringResource(R.string.backup_password_hint)) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it; error = null },
                    label = { Text(stringResource(R.string.backup_password_confirm_hint)) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pwd.isEmpty() -> error = emptyMsg
                    pwd.length < 8 -> error = tooShortMsg
                    pwd != confirm -> error = mismatchMsg
                    else -> onConfirm(pwd.toCharArray())
                }
            }) { Text(stringResource(R.string.backup_export_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) }
        }
    )
}

@Composable
private fun ImportPasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit
) {
    var pwd by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val emptyMsg = stringResource(R.string.backup_password_empty)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_password_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it; error = null },
                    label = { Text(stringResource(R.string.backup_password_import_hint)) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (pwd.isEmpty()) error = emptyMsg
                else onConfirm(pwd.toCharArray())
            }) { Text(stringResource(R.string.backup_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) }
        }
    )
}
