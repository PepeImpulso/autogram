package com.autogram.app.ui.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.autogram.app.R
import com.autogram.app.infrastructure.telegram.mtproto.AuthState
import com.autogram.app.ui.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity login flow against TDLib. The visible step is driven by
 * [AuthState] emitted by the native client, not by UI-side navigation. That
 * means resuming a half-completed login (e.g. user closed the app after typing
 * phone but before code) resumes automatically on the right step.
 */
@AndroidEntryPoint
class TelegramLoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutogramTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TelegramLoginScreen(onLoggedIn = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        )
                        finish()
                    })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TelegramLoginScreen(
    onLoggedIn: () -> Unit,
    viewModel: TelegramLoginViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsState()
    val ctx = LocalContext.current

    LaunchedEffect(ui.authState) {
        if (ui.authState is AuthState.Ready) onLoggedIn()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(ctx.getString(R.string.tg_login_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Onboarding branding — logo + app name so the setup flow matches
            // the rest of the app visually.
            Image(
                painter = painterResource(R.drawable.autogram_logo),
                contentDescription = null,
                modifier = Modifier.size(96.dp)
            )
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))

            when (val s = ui.authState) {
                AuthState.Uninitialized, AuthState.Initializing -> InitializingStep()
                AuthState.WaitPhoneNumber -> PhoneStep(ui, viewModel)
                is AuthState.WaitCode -> CodeStep(ui, viewModel, s)
                is AuthState.WaitPassword -> PasswordStep(ui, viewModel, s)
                AuthState.Ready -> Text(ctx.getString(R.string.tg_login_ready))
                AuthState.LoggingOut -> Text(ctx.getString(R.string.tg_login_logging_out))
                AuthState.Closed -> Text(ctx.getString(R.string.tg_login_closed))
                is AuthState.Error -> ErrorStep(s.message)
            }

            ui.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun InitializingStep() {
    val ctx = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(ctx.getString(R.string.tg_login_initializing))
    }
}

@Composable
private fun PhoneStep(ui: TelegramLoginViewModel.UiState, vm: TelegramLoginViewModel) {
    val ctx = LocalContext.current
    Text(ctx.getString(R.string.tg_login_phone_title), style = MaterialTheme.typography.headlineSmall)
    Text(ctx.getString(R.string.tg_login_phone_body), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = ui.phoneInput,
        onValueChange = vm::setPhone,
        singleLine = true,
        label = { Text(ctx.getString(R.string.tg_login_phone_label)) },
        placeholder = { Text(ctx.getString(R.string.tg_login_phone_placeholder)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = vm::submitPhone,
        enabled = !ui.busy && ui.phoneInput.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (ui.busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        else Text(ctx.getString(R.string.tg_login_phone_send))
    }
}

@Composable
private fun CodeStep(
    ui: TelegramLoginViewModel.UiState,
    vm: TelegramLoginViewModel,
    waiting: AuthState.WaitCode
) {
    val ctx = LocalContext.current
    val hint = if (waiting.codeLength > 0)
        ctx.getString(R.string.tg_login_code_digits, waiting.codeLength)
    else
        ctx.getString(R.string.tg_login_code_generic)

    Text(ctx.getString(R.string.tg_login_code_title), style = MaterialTheme.typography.headlineSmall)
    Text(ctx.getString(R.string.tg_login_code_body, hint), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = ui.codeInput,
        onValueChange = vm::setCode,
        singleLine = true,
        label = { Text(ctx.getString(R.string.tg_login_code_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = vm::submitCode,
        enabled = !ui.busy && ui.codeInput.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (ui.busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        else Text(ctx.getString(R.string.tg_login_code_verify))
    }
}

@Composable
private fun PasswordStep(
    ui: TelegramLoginViewModel.UiState,
    vm: TelegramLoginViewModel,
    waiting: AuthState.WaitPassword
) {
    val ctx = LocalContext.current
    Text(ctx.getString(R.string.tg_login_2fa_title), style = MaterialTheme.typography.headlineSmall)
    Text(ctx.getString(R.string.tg_login_2fa_body), style = MaterialTheme.typography.bodyMedium)
    waiting.hint?.let {
        Text(ctx.getString(R.string.tg_login_2fa_hint, it), style = MaterialTheme.typography.bodySmall)
    }
    OutlinedTextField(
        value = ui.passwordInput,
        onValueChange = vm::setPassword,
        singleLine = true,
        label = { Text(ctx.getString(R.string.tg_login_2fa_label)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = vm::submitPassword,
        enabled = !ui.busy && ui.passwordInput.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (ui.busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        else Text(ctx.getString(R.string.tg_login_2fa_submit))
    }
}

@Composable
private fun ErrorStep(message: String) {
    val ctx = LocalContext.current
    Text(ctx.getString(R.string.tg_login_error_title), style = MaterialTheme.typography.headlineSmall)
    Text(message, color = MaterialTheme.colorScheme.error)
    Text(ctx.getString(R.string.tg_login_error_retry), style = MaterialTheme.typography.bodySmall)
}
