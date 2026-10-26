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
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.autogram.app.BuildConfig
import com.autogram.app.R
import com.autogram.app.infrastructure.audio.PlaybackSpeed
import com.autogram.app.infrastructure.platform.DistributionCrashReporter
import kotlinx.coroutines.launch

// Must match ExportImportUseCase.MAX_BACKUP_BYTES so the pre-read guard
// rejects the same set of files the use case would reject later (and
// nothing extra). Prevents readBytes() from OOM-ing on a user who picks
// a multi-gigabyte file via the SAF picker.
private const val MAX_IMPORT_BYTES: Long = 50L * 1024L * 1024L

/**
 * Configuración — visual premium: tarjetas de sección con icono cabecero, filas
 * con estilo ListItem y separadores finos, paleta de surfaces de Material 3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    initialScrollOffset: Int = 0
) {
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
    var showEraseLocalDataDialog by remember { mutableStateOf(false) }

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
                // Reject oversize files BEFORE readBytes() loads the whole
                // thing into memory. The user could pick a multi-GB file
                // (video, archive, anything) via the SAF picker and OOM the
                // process before ExportImportUseCase's 50 MB check ever
                // fires. `statSize` over a ContentProvider-backed URI is
                // sometimes -1 (unknown) — in that case we fall back to a
                // bounded read and reject if the bytes exceed the cap.
                val fdSize = context.contentResolver.openFileDescriptor(uri, "r")
                    ?.use { it.statSize } ?: -1L
                if (fdSize in 1L..MAX_IMPORT_BYTES) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("openInputStream returned null")
                } else if (fdSize > MAX_IMPORT_BYTES) {
                    error("file too large (${fdSize / 1024 / 1024} MB)")
                } else {
                    // size unknown — read up to MAX_IMPORT_BYTES + 1 and
                    // reject if we got more, so we never hold the full
                    // body in memory beyond the cap.
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val buf = ByteArray((MAX_IMPORT_BYTES + 1).toInt())
                        var total = 0
                        while (total < buf.size) {
                            val r = stream.read(buf, total, buf.size - total)
                            if (r == -1) break
                            total += r
                        }
                        if (total > MAX_IMPORT_BYTES) error("file too large")
                        buf.copyOf(total)
                    } ?: error("openInputStream returned null")
                }
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
                SettingsViewModel.BackupEvent.ImportUnsupportedVersion ->
                    android.widget.Toast.makeText(context, context.getString(R.string.backup_import_unsupported), android.widget.Toast.LENGTH_LONG).show()
                is SettingsViewModel.BackupEvent.ImportFailed ->
                    android.widget.Toast.makeText(context, context.getString(R.string.backup_import_fail, event.message), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.dataEraseEvents.collect { event ->
            when (event) {
                SettingsViewModel.DataEraseEvent.Success -> {
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                        androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                    )
                    context.startActivity(
                        android.content.Intent(context, TelegramLoginActivity::class.java).apply {
                            addFlags(
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                            )
                        }
                    )
                }
                is SettingsViewModel.DataEraseEvent.Failed ->
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.settings_data_erase_failed, event.message),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
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

    if (showEraseLocalDataDialog) {
        AlertDialog(
            onDismissRequest = { showEraseLocalDataDialog = false },
            title = { Text(stringResource(R.string.settings_data_erase_confirm_title)) },
            text = { Text(stringResource(R.string.settings_data_erase_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEraseLocalDataDialog = false
                        viewModel.eraseLocalData()
                    }
                ) { Text(stringResource(R.string.settings_data_erase_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showEraseLocalDataDialog = false }) {
                    Text(stringResource(R.string.backup_cancel))
                }
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
                .clipToBounds()
                .verticalScroll(rememberScrollState(initialScrollOffset))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Pulled to the top of Settings per user request: the first thing
            // the user should see is whether their app is up to date. Title +
            // installed version share a single line; only the "Check for
            // updates" button lives below it.
            if (BuildConfig.ENABLE_SELF_UPDATE) {
                val updateViewModel: UpdateViewModel = androidx.hilt.navigation.compose.hiltViewModel()
                SectionCard(
                    icon = Icons.Filled.SystemUpdate,
                    title = stringResource(R.string.settings_updates_section) +
                        " - " +
                        stringResource(
                            R.string.settings_updates_current_version,
                            updateViewModel.currentVersion
                        )
                ) {
                    UpdatesRow(updateViewModel)
                }
            }

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
                // v1.20.11: silent mode collapsed into auto-play. The toggle
                // here is the single source of truth — OFF means "queue
                // every incoming reply for manual playback from HeroHeader's
                // 'Play N pending' button" (the old silent-mode semantic).
                SwitchRow(
                    label = stringResource(R.string.settings_autoplay),
                    supporting = stringResource(R.string.settings_autoplay_sub),
                    checked = state.autoPlay,
                    onCheckedChange = viewModel::updateAutoPlay
                )
                SettingsDivider()
                SliderRow(
                    label = stringResource(R.string.settings_playback_speed),
                    value = state.playbackSpeed,
                    valueLabel = "${"%.2f".format(state.playbackSpeed)}x",
                    onValueChange = viewModel::updatePlaybackSpeed,
                    valueRange = PlaybackSpeed.MIN..PlaybackSpeed.MAX,
                    steps = 29
                )
                SettingsDivider()
                SliderRow(
                    label = stringResource(R.string.settings_recording_cues),
                    supporting = stringResource(R.string.settings_recording_cues_sub),
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
                SettingsDivider()
                SwitchRow(
                    label = stringResource(R.string.settings_show_pending_badges),
                    supporting = stringResource(R.string.settings_show_pending_badges_sub),
                    checked = state.showPendingBadges,
                    onCheckedChange = viewModel::updateShowPendingBadges
                )
                SettingsDivider()
                SwitchRow(
                    label = stringResource(R.string.settings_keep_screen_on),
                    supporting = stringResource(R.string.settings_keep_screen_on_sub),
                    checked = state.keepScreenOn,
                    onCheckedChange = viewModel::updateKeepScreenOn
                )
            }

            // v1.24.0 — Android Auto audio-routing diagnostics. Lives just
            // below Playback so users hitting "voice didn't reach the car
            // speaker" find it next to the related controls.
            SectionCard(
                icon = Icons.Filled.DirectionsCar,
                title = stringResource(R.string.settings_section_aa_routing),
                subtitle = stringResource(R.string.settings_section_aa_routing_sub)
            ) {
                AudioFocusModeSelector(
                    current = state.audioFocusMode,
                    onSelect = viewModel::updateAudioFocusMode
                )
                SettingsDivider()
                SwitchRow(
                    label = stringResource(R.string.settings_aa_tts_nav_prompt),
                    supporting = stringResource(R.string.settings_aa_tts_nav_prompt_sub),
                    checked = state.ttsAsNavigationPrompt,
                    onCheckedChange = viewModel::updateTtsAsNavigationPrompt
                )
                SettingsDivider()
                SwitchRow(
                    label = stringResource(R.string.settings_aa_car_grid_buttons),
                    supporting = stringResource(R.string.settings_aa_car_grid_buttons_sub),
                    checked = state.carGridButtons,
                    onCheckedChange = viewModel::updateCarGridButtons
                )
                SettingsDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_aa_test_playback),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            stringResource(R.string.settings_aa_test_playback_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = viewModel::runTestPlayback) {
                        Text(stringResource(R.string.settings_aa_test_playback))
                    }
                }
            }

            SectionCard(
                icon = Icons.Filled.TextFields,
                title = stringResource(R.string.settings_section_transcribe),
                subtitle = stringResource(R.string.settings_section_transcribe_sub)
            ) {
                val transcribeSupported =
                    com.autogram.app.infrastructure.transcription.AndroidOnDeviceTranscriptionEngine.isSupportedHere(context)
                SwitchRow(
                    label = stringResource(R.string.settings_transcribe),
                    supporting = if (transcribeSupported) stringResource(R.string.settings_transcribe_sub)
                                 else stringResource(R.string.settings_transcribe_unsupported),
                    checked = state.transcribeIncomingVoice && transcribeSupported,
                    onCheckedChange = { enabled ->
                        if (transcribeSupported) viewModel.updateTranscribe(enabled)
                    }
                )
            }

            SectionCard(
                icon = Icons.Filled.Summarize,
                title = stringResource(R.string.settings_section_summary),
                subtitle = stringResource(R.string.settings_section_summary_sub)
            ) {
                SummarySection(
                    enabled = state.summarizeLongMessages,
                    engine = state.summaryEngine,
                    endpoint = state.summaryLlmEndpoint,
                    apiKey = state.summaryLlmApiKey,
                    model = state.summaryLlmModel,
                    onEnabledChange = viewModel::updateSummarize,
                    onEngineChange = viewModel::updateSummaryEngine,
                    onEndpointChange = viewModel::updateSummaryLlmEndpoint,
                    onApiKeyChange = viewModel::updateSummaryLlmApiKey,
                    onModelChange = viewModel::updateSummaryLlmModel
                )
            }

            var showNoBiometricDialog by remember { mutableStateOf(false) }
            SectionCard(
                icon = Icons.Filled.Fingerprint,
                title = stringResource(R.string.settings_section_security),
                subtitle = stringResource(R.string.settings_section_security_sub)
            ) {
                SwitchRow(
                    label = stringResource(R.string.settings_biometric_lock),
                    supporting = stringResource(R.string.settings_biometric_lock_sub),
                    checked = state.biometricLockEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && !com.autogram.app.infrastructure.platform.BiometricGate.isSupported(context)) {
                            // The system has nothing to prompt with — don't
                            // silently flip the toggle on and let the user
                            // believe they locked the app.
                            showNoBiometricDialog = true
                        } else {
                            viewModel.updateBiometricLock(enabled)
                        }
                    }
                )
            }
            if (showNoBiometricDialog) {
                AlertDialog(
                    onDismissRequest = { showNoBiometricDialog = false },
                    icon = { Icon(Icons.Filled.Fingerprint, contentDescription = null) },
                    title = { Text(stringResource(R.string.settings_biometric_missing_title)) },
                    text = { Text(stringResource(R.string.settings_biometric_missing_body)) },
                    confirmButton = {
                        TextButton(onClick = {
                            showNoBiometricDialog = false
                            runCatching {
                                // ACTION_BIOMETRIC_ENROLL is API 30+. On older
                                // devices fall back to the generic Security
                                // settings screen.
                                val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                    android.content.Intent(android.provider.Settings.ACTION_BIOMETRIC_ENROLL).apply {
                                        putExtra(
                                            android.provider.Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                                            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
                                                    or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                                        )
                                    }
                                } else {
                                    android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                                }
                                context.startActivity(intent)
                            }
                        }) { Text(stringResource(R.string.settings_biometric_open_enrol)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showNoBiometricDialog = false }) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
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
                icon = Icons.Filled.Security,
                title = stringResource(R.string.settings_data_section),
                subtitle = stringResource(R.string.settings_data_section_sub)
            ) {
                NavigationRow(
                    label = stringResource(R.string.settings_privacy_policy),
                    supporting = stringResource(R.string.settings_privacy_policy_sub),
                    onClick = {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(BuildConfig.PRIVACY_POLICY_URL)
                            )
                        )
                    }
                )
                SettingsDivider()
                NavigationRow(
                    label = stringResource(R.string.settings_terms),
                    supporting = stringResource(R.string.settings_terms_sub),
                    onClick = {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(BuildConfig.ACCEPTABLE_USE_URL)
                            )
                        )
                    }
                )
                SettingsDivider()
                NavigationRow(
                    label = stringResource(R.string.settings_data_erase),
                    supporting = stringResource(R.string.settings_data_erase_sub),
                    onClick = { showEraseLocalDataDialog = true }
                )
            }

            SectionCard(
                icon = Icons.Filled.Battery4Bar,
                title = stringResource(R.string.settings_battery_section)
            ) {
                BatteryOptimizationRow(context)
            }

            // v1.24.3 — unified Diagnostics card. Pulls in the last-playback
            // route (was on AA routing) + debug-log share/clear (was its own
            // card) so everything debug-related lives in one place. The
            // primary "Send bug report" CTA captures a Sentry event with the
            // rolling log file attached automatically — one tap is all the
            // user has to do; no separate manual share required.
            SectionCard(
                icon = Icons.Filled.BugReport,
                title = stringResource(R.string.settings_diagnostics_section)
            ) {
                AaRoutingDiagnosticsRow(attempt = state.lastPlaybackAttempt)
                SettingsDivider()
                BugReportRow()
                SettingsDivider()
                DebugLogsRow()
                SettingsDivider()
                SwitchRow(
                    label = stringResource(R.string.settings_developer_mode),
                    supporting = stringResource(R.string.settings_developer_mode_sub),
                    checked = state.developerMode,
                    onCheckedChange = viewModel::updateDeveloperMode
                )
            }
        }
    }
}

/**
 * v1.24.3 — primary "Send bug report" CTA. Replaces the old "Send test
 * event to Sentry" affordance and merges in the debug-log file attach
 * so the user gets a one-tap report:
 *
 *   1. Materialise the rolling debug log via [FileLoggingTree].
 *   2. Attach the file to a Sentry scope.
 *   3. Capture a message-level event tagged `manual_report=true` so the
 *      developer can spot it amid auto-captured crashes.
 *
 * When Sentry isn't configured (empty DSN in dev builds) the row falls
 * back to the system share intent — same payload, manual delivery.
 */
@Composable
private fun BugReportRow() {
    val context = LocalContext.current
    var sentAtMs by remember { mutableStateOf(0L) }
    val justSent = sentAtMs != 0L && System.currentTimeMillis() - sentAtMs < 3000
    val hasSentry = DistributionCrashReporter.isConfigured
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val tree = (context.applicationContext as? com.autogram.app.AutogramApplication)
                    ?.fileLoggingTree
                val logFile = runCatching { tree?.materialiseExportFile() }.getOrNull()
                if (hasSentry) {
                    DistributionCrashReporter.captureManualReport(logFile)
                        .onSuccess {
                            sentAtMs = System.currentTimeMillis()
                        }
                        .onFailure {
                            android.widget.Toast.makeText(
                                context,
                                it.message ?: context.getString(R.string.settings_bug_report_failed),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                } else {
                    // Sentry disabled — share the log file via the system
                    // chooser instead. Same payload, manual delivery.
                    val uri = tree?.exportUri()
                    if (uri == null) {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.settings_debug_logs_unavailable),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        return@clickable
                    }
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            android.content.Intent.EXTRA_STREAM,
                            uri as android.os.Parcelable
                        )
                        putExtra(
                            android.content.Intent.EXTRA_SUBJECT,
                            context.getString(R.string.settings_debug_logs_share_subject)
                        )
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching {
                        context.startActivity(
                            android.content.Intent.createChooser(
                                intent,
                                context.getString(R.string.settings_bug_report_share)
                            )
                        )
                    }
                }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.settings_bug_report),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                when {
                    justSent -> stringResource(R.string.settings_bug_report_sent)
                    hasSentry -> stringResource(R.string.settings_bug_report_sub_sentry)
                    else -> stringResource(R.string.settings_bug_report_sub_share)
                },
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
    valueLabelDim: Boolean = false,
    supporting: String? = null
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
        if (!supporting.isNullOrBlank()) {
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
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

/**
 * v1.24.0 — three-way picker for [AudioFocusMode]. Same look as
 * [LanguageSelector] (radio rows) so it fits inside the AA routing
 * section card without introducing a new control style. Tapping a row
 * persists the value via [SettingsViewModel.updateAudioFocusMode].
 */
@Composable
private fun AudioFocusModeSelector(
    current: com.autogram.app.domain.model.AudioFocusMode,
    onSelect: (com.autogram.app.domain.model.AudioFocusMode) -> Unit
) {
    val options = listOf(
        com.autogram.app.domain.model.AudioFocusMode.TRANSIENT_MAY_DUCK to
            stringResource(R.string.settings_aa_focus_transient_may_duck),
        com.autogram.app.domain.model.AudioFocusMode.TRANSIENT_EXCLUSIVE to
            stringResource(R.string.settings_aa_focus_transient),
        com.autogram.app.domain.model.AudioFocusMode.GAIN to
            stringResource(R.string.settings_aa_focus_gain)
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_aa_focus_mode),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Text(
            text = stringResource(R.string.settings_aa_focus_mode_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        options.forEachIndexed { i, (mode, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = mode == current,
                    onClick = { onSelect(mode) }
                )
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
            if (i < options.lastIndex) SettingsDivider()
        }
    }
}

/**
 * v1.24.1 — full PlaybackAttempt diagnostics row. Shows source
 * (AudioPlayer / TTS), focus result (granted/denied), routed device
 * label, focus mode used, and timestamp. Tells the user — in-car — not
 * just where the audio went, but why it went there (or why nothing
 * played at all): "TTS · denied · phone speaker · Transient+Duck" is a
 * very different diagnosis from "AudioPlayer · granted · BUS".
 */
@Composable
private fun AaRoutingDiagnosticsRow(
    attempt: com.autogram.app.infrastructure.audio.PlaybackDiagnostics.PlaybackAttempt?
) {
    if (attempt == null) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.settings_aa_last_routed),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(R.string.settings_aa_last_routed_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val routeLabel = when (attempt.routedDeviceType) {
        null -> "unknown"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP (car)"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        android.media.AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE headset"
        android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE speaker"
        android.media.AudioDeviceInfo.TYPE_BUS -> "Bus (AA media stream)"
        android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
        android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        android.media.AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        android.media.AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
        android.media.AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI eARC"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker (NOT the car)"
        android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone earpiece"
        android.media.AudioDeviceInfo.TYPE_LINE_ANALOG -> "Analog line"
        android.media.AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital line"
        android.media.AudioDeviceInfo.TYPE_AUX_LINE -> "AUX line"
        android.media.AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
        else -> "Type ${attempt.routedDeviceType}"
    }
    val sourceLabel = when (attempt.source) {
        com.autogram.app.infrastructure.audio.PlaybackDiagnostics.Source.AUDIO_PLAYER -> "AudioPlayer"
        com.autogram.app.infrastructure.audio.PlaybackDiagnostics.Source.TTS_PLAYER -> "TTS"
    }
    val focusLabel = if (attempt.focusGranted) "granted" else "denied (played anyway)"
    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(attempt.timestamp))
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.settings_aa_last_routed),
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "$sourceLabel · $routeLabel",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "focus=$focusLabel · mode=${attempt.focusMode.name} · $time",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * v1.24.2 — pair of buttons that share or wipe the rolling debug log
 * file maintained by [com.autogram.app.infrastructure.platform.FileLoggingTree].
 * The share intent hands the FileProvider Uri to whatever messaging /
 * email app the user picks; the clear button is so the user can start
 * a clean capture before reproducing a bug.
 */
@Composable
private fun DebugLogsRow() {
    val context = LocalContext.current
    val tree = (context.applicationContext as? com.autogram.app.AutogramApplication)
        ?.fileLoggingTree
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            modifier = Modifier.weight(1f),
            onClick = {
                val uri = tree?.exportUri()
                if (uri == null) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.settings_debug_logs_unavailable),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@Button
                }
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    // Uri implements both Parcelable and Serializable, so we
                    // cast to disambiguate the putExtra overload.
                    putExtra(android.content.Intent.EXTRA_STREAM, uri as android.os.Parcelable)
                    putExtra(
                        android.content.Intent.EXTRA_SUBJECT,
                        context.getString(R.string.settings_debug_logs_share_subject)
                    )
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    context.startActivity(
                        android.content.Intent.createChooser(
                            intent,
                            context.getString(R.string.settings_debug_logs_share)
                        )
                    )
                }.onFailure {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.settings_debug_logs_unavailable),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        ) {
            Text(stringResource(R.string.settings_debug_logs_share))
        }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(
            modifier = Modifier.weight(1f),
            onClick = {
                tree?.clear()
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.settings_debug_logs_cleared),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        ) {
            Text(stringResource(R.string.settings_debug_logs_clear))
        }
    }
}

@Composable
private fun UpdatesRow(viewModel: UpdateViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    val ctx = LocalContext.current
    val state by viewModel.state.collectAsState()
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        // The installed version moved into the section card's title
        // (rendered as "Updates - Installed version: X.Y.Z"), so this
        // row only needs the transient status + the Check button.
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
            when (val current = state) {
                is UpdateViewModel.State.Checking -> {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(ctx.getString(R.string.settings_updates_checking))
                }
                is UpdateViewModel.State.Downloading -> {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(current.message ?: ctx.getString(R.string.update_downloading))
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
    // String is immutable — we can't zeroize its char[] in-place. But dropping
    // the reference (setting to "") lets GC reclaim it. Always clear on unmount.
    DisposableEffect(Unit) {
        onDispose { pwd = ""; confirm = ""; error = null }
    }
    val handleDismiss = { pwd = ""; confirm = ""; error = null; onDismiss() }
    AlertDialog(
        onDismissRequest = handleDismiss,
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
                error?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pwd.isEmpty() -> error = emptyMsg
                    pwd.length < 8 -> error = tooShortMsg
                    pwd != confirm -> error = mismatchMsg
                    else -> {
                        val chars = pwd.toCharArray()
                        // Drop references before invoking the callback — the
                        // callback's caller owns zeroizing the CharArray copy.
                        pwd = ""; confirm = ""; error = null
                        onConfirm(chars)
                    }
                }
            }) { Text(stringResource(R.string.backup_export_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = handleDismiss) { Text(stringResource(R.string.backup_cancel)) }
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
    DisposableEffect(Unit) {
        onDispose { pwd = ""; error = null }
    }
    val handleDismiss = { pwd = ""; error = null; onDismiss() }
    AlertDialog(
        onDismissRequest = handleDismiss,
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
                error?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (pwd.isEmpty()) error = emptyMsg
                else {
                    val chars = pwd.toCharArray()
                    pwd = ""; error = null
                    onConfirm(chars)
                }
            }) { Text(stringResource(R.string.backup_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = handleDismiss) { Text(stringResource(R.string.backup_cancel)) }
        }
    )
}

@Composable
private fun SummarySection(
    enabled: Boolean,
    engine: com.autogram.app.domain.model.SummaryEngineType,
    endpoint: String,
    apiKey: String,
    model: String,
    onEnabledChange: (Boolean) -> Unit,
    onEngineChange: (com.autogram.app.domain.model.SummaryEngineType) -> Unit,
    onEndpointChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit
) {
    var showExternalLlmDisclosure by remember { mutableStateOf(false) }
    fun selectEngine(type: com.autogram.app.domain.model.SummaryEngineType) {
        if (type == com.autogram.app.domain.model.SummaryEngineType.LLM_ENDPOINT && engine != type) {
            showExternalLlmDisclosure = true
        } else {
            onEngineChange(type)
        }
    }

    if (showExternalLlmDisclosure) {
        AlertDialog(
            onDismissRequest = { showExternalLlmDisclosure = false },
            title = { Text(stringResource(R.string.settings_summary_llm_disclosure_title)) },
            text = { Text(stringResource(R.string.settings_summary_llm_disclosure_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showExternalLlmDisclosure = false
                    onEngineChange(com.autogram.app.domain.model.SummaryEngineType.LLM_ENDPOINT)
                }) {
                    Text(stringResource(R.string.settings_summary_llm_disclosure_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExternalLlmDisclosure = false }) {
                    Text(stringResource(R.string.backup_cancel))
                }
            }
        )
    }

    SwitchRow(
        label = stringResource(R.string.settings_summarize),
        checked = enabled,
        onCheckedChange = onEnabledChange
    )
    if (!enabled) return
    SettingsDivider()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.settings_summary_engine),
            style = MaterialTheme.typography.labelLarge
        )
        val options = listOf(
            com.autogram.app.domain.model.SummaryEngineType.NONE to R.string.settings_summary_engine_none,
            com.autogram.app.domain.model.SummaryEngineType.GEMINI_NANO to R.string.settings_summary_engine_gemini,
            com.autogram.app.domain.model.SummaryEngineType.LLM_ENDPOINT to R.string.settings_summary_engine_llm
        )
        options.forEach { (type, stringRes) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectEngine(type) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = engine == type,
                    onClick = { selectEngine(type) }
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(stringRes))
            }
        }
    }
    if (engine == com.autogram.app.domain.model.SummaryEngineType.LLM_ENDPOINT) {
        SettingsDivider()
        Text(
            text = stringResource(R.string.settings_summary_llm_disclosure_reminder),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        DebouncedTextField(
            initial = endpoint,
            onCommit = onEndpointChange,
            label = stringResource(R.string.settings_summary_llm_endpoint),
            placeholder = stringResource(R.string.settings_summary_llm_endpoint_hint),
            masked = false
        )
        DebouncedTextField(
            initial = apiKey,
            onCommit = onApiKeyChange,
            label = stringResource(R.string.settings_summary_llm_api_key),
            placeholder = "",
            masked = true
        )
        DebouncedTextField(
            initial = model,
            onCommit = onModelChange,
            label = stringResource(R.string.settings_summary_llm_model),
            placeholder = stringResource(R.string.settings_summary_llm_model_hint),
            masked = false
        )
    }
}

@Composable
private fun DebouncedTextField(
    initial: String,
    onCommit: (String) -> Unit,
    label: String,
    placeholder: String,
    masked: Boolean
) {
    var text by remember(initial) { mutableStateOf(initial) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            job?.cancel()
            job = scope.launch {
                kotlinx.coroutines.delay(500)
                onCommit(it)
            }
        },
        label = { Text(label) },
        placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
        singleLine = true,
        visualTransformation = if (masked) androidx.compose.ui.text.input.PasswordVisualTransformation()
                              else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    )
}
