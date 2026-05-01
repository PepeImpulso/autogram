package com.autogram.app.ui.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.autogram.app.R
import com.autogram.app.ui.phone.BaseLocaleActivity
import com.autogram.app.ui.phone.SettingsActivity
import com.autogram.app.ui.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * v1.22.0 / issue #63 — Android digital-assistant entry point.
 *
 * Phase 1 (v1.21.0+): manifest registration so Autogram surfaces in
 * Samsung's "Default digital assistant app" picker.
 *
 * Phase 2 (this revision): wires the actual voice loop. Records the
 * user's voice via the existing [com.autogram.app.domain.usecase.SendVoiceMtprotoUseCase],
 * sends it as a voice note to the configured primary target (Merlin
 * by default), waits for the next incoming message on that
 * conversation, then closes so Autogram's normal autoplay pipeline
 * reads the reply aloud.
 */
@AndroidEntryPoint
class AssistantActivity : BaseLocaleActivity() {
    private val viewModel: AssistantViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutogramTheme {
                AssistantScreen(
                    viewModel = viewModel,
                    onClose = { finish() },
                    onOpenSettings = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                )
            }
        }
    }
}

@Composable
private fun AssistantScreen(
    viewModel: AssistantViewModel,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
    }

    // Auto-close once Merlin's reply has arrived. Existing autoplay
    // pipeline takes over and reads it aloud.
    LaunchedEffect(state) {
        if (state is AssistantViewModel.State.Replied) {
            onClose()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                !permissionGranted -> PermissionContent(
                    onGrant = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onClose = onClose
                )
                state is AssistantViewModel.State.NoTarget -> NoTargetContent(
                    onOpenSettings = onOpenSettings,
                    onClose = onClose
                )
                state is AssistantViewModel.State.Failed -> FailedContent(
                    reason = (state as AssistantViewModel.State.Failed).reason,
                    onRetry = viewModel::reset,
                    onClose = onClose
                )
                state is AssistantViewModel.State.Idle -> IdleContent(
                    onStart = viewModel::startListening,
                    onClose = onClose
                )
                state is AssistantViewModel.State.Listening -> ListeningContent(
                    targetTitle = (state as AssistantViewModel.State.Listening).targetTitle,
                    onStop = viewModel::stopAndSend,
                    onCancel = viewModel::cancel
                )
                state is AssistantViewModel.State.Sending -> SendingContent(
                    targetTitle = (state as AssistantViewModel.State.Sending).targetTitle
                )
                state is AssistantViewModel.State.WaitingForReply -> WaitingContent(
                    targetTitle = (state as AssistantViewModel.State.WaitingForReply).targetTitle,
                    onClose = onClose
                )
                state is AssistantViewModel.State.Replied -> {
                    // Transition handled by the LaunchedEffect above; render
                    // a brief confirmation while finish() runs.
                    Text(
                        stringResource(R.string.assistant_replied_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionContent(onGrant: () -> Unit, onClose: () -> Unit) {
    Icon(
        imageVector = Icons.Filled.Mic,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(72.dp)
    )
    Spacer(Modifier.height(20.dp))
    Text(
        stringResource(R.string.assistant_perm_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(R.string.assistant_perm_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(28.dp))
    Button(onClick = onGrant) {
        Text(stringResource(R.string.assistant_perm_grant))
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onClose) {
        Text(stringResource(R.string.assistant_placeholder_dismiss))
    }
}

@Composable
private fun IdleContent(onStart: () -> Unit, onClose: () -> Unit) {
    Text(
        stringResource(R.string.assistant_idle_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(28.dp))
    FilledIconButton(
        onClick = onStart,
        modifier = Modifier.size(96.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = stringResource(R.string.assistant_idle_button),
            modifier = Modifier.size(48.dp)
        )
    }
    Spacer(Modifier.height(28.dp))
    TextButton(onClick = onClose) {
        Text(stringResource(R.string.assistant_placeholder_dismiss))
    }
}

@Composable
private fun ListeningContent(
    targetTitle: String,
    onStop: () -> Unit,
    onCancel: () -> Unit
) {
    Text(
        stringResource(R.string.assistant_listening_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.assistant_listening_subtitle, targetTitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(28.dp))
    FilledIconButton(
        onClick = onStop,
        modifier = Modifier.size(96.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
        )
    ) {
        Icon(
            imageVector = Icons.Filled.Stop,
            contentDescription = stringResource(R.string.assistant_listening_stop),
            modifier = Modifier.size(48.dp)
        )
    }
    Spacer(Modifier.height(28.dp))
    TextButton(onClick = onCancel) {
        Text(stringResource(R.string.assistant_listening_cancel))
    }
}

@Composable
private fun SendingContent(targetTitle: String) {
    CircularProgressIndicator(modifier = Modifier.size(64.dp))
    Spacer(Modifier.height(20.dp))
    Text(
        stringResource(R.string.assistant_sending_title, targetTitle),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun WaitingContent(targetTitle: String, onClose: () -> Unit) {
    CircularProgressIndicator(modifier = Modifier.size(64.dp))
    Spacer(Modifier.height(20.dp))
    Text(
        stringResource(R.string.assistant_waiting_title, targetTitle),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.assistant_waiting_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(28.dp))
    TextButton(onClick = onClose) {
        Text(stringResource(R.string.assistant_waiting_close))
    }
}

@Composable
private fun FailedContent(
    reason: String,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    Text(
        stringResource(R.string.assistant_failed_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(10.dp))
    Text(
        reason,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(28.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onClose) {
            Text(stringResource(R.string.assistant_placeholder_dismiss))
        }
        Spacer(Modifier.width(12.dp))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.assistant_failed_retry))
        }
    }
}

@Composable
private fun NoTargetContent(onOpenSettings: () -> Unit, onClose: () -> Unit) {
    Text(
        stringResource(R.string.assistant_no_target_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(R.string.assistant_no_target_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(28.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onClose) {
            Text(stringResource(R.string.assistant_placeholder_dismiss))
        }
        Spacer(Modifier.width(12.dp))
        Button(onClick = onOpenSettings) {
            Text(stringResource(R.string.assistant_no_target_open_settings))
        }
    }
}
