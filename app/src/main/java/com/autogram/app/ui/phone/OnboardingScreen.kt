package com.autogram.app.ui.phone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.autogram.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val ctx = LocalContext.current

    LaunchedEffect(state.finished) {
        if (state.finished) onFinished()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ctx.getString(R.string.app_name)) },
                navigationIcon = {
                    if (state.step != OnboardingViewModel.Step.WELCOME) {
                        TextButton(onClick = { viewModel.goBack() }) {
                            Text(ctx.getString(R.string.onboarding_back))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start
        ) {
            when (state.step) {
                OnboardingViewModel.Step.WELCOME -> WelcomeStep(viewModel)
                OnboardingViewModel.Step.TOKEN -> TokenStep(state, viewModel)
                OnboardingViewModel.Step.CHAT -> ChatStep(state, viewModel)
                OnboardingViewModel.Step.DONE -> Text(ctx.getString(R.string.onboarding_finish))
            }
        }
    }
}

@Composable
private fun WelcomeStep(viewModel: OnboardingViewModel) {
    val ctx = LocalContext.current
    Text(
        text = ctx.getString(R.string.onboarding_welcome_title),
        style = MaterialTheme.typography.headlineSmall
    )
    Spacer(Modifier.height(16.dp))
    Text(ctx.getString(R.string.onboarding_welcome_body))
    Spacer(Modifier.height(32.dp))
    Button(onClick = { viewModel.goNext() }, modifier = Modifier.fillMaxWidth()) {
        Text(ctx.getString(R.string.onboarding_continue))
    }
}

@Composable
private fun TokenStep(state: OnboardingViewModel.UiState, viewModel: OnboardingViewModel) {
    val ctx = LocalContext.current
    Text(ctx.getString(R.string.onboarding_token_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text(ctx.getString(R.string.onboarding_token_body))
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = state.token,
        onValueChange = viewModel::setToken,
        label = { Text("Bot token") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        isError = state.tokenError != null
    )
    if (state.tokenError == "format_invalid") {
        Text(
            ctx.getString(R.string.onboarding_invalid_token),
            color = MaterialTheme.colorScheme.error
        )
    } else if (state.tokenError != null) {
        Text(
            ctx.getString(R.string.onboarding_connection_error, state.tokenError),
            color = MaterialTheme.colorScheme.error
        )
    }
    Spacer(Modifier.height(16.dp))
    Row {
        Button(
            onClick = { viewModel.testToken() },
            enabled = !state.testing && state.token.isNotBlank()
        ) {
            if (state.testing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(ctx.getString(R.string.onboarding_test_connection))
        }
        Spacer(Modifier.width(12.dp))
        Button(
            onClick = { viewModel.goNext() },
            enabled = state.tokenValid
        ) {
            Text(ctx.getString(R.string.onboarding_continue))
        }
    }
    if (state.tokenValid) {
        Spacer(Modifier.height(8.dp))
        Text(
            ctx.getString(R.string.onboarding_connection_ok, state.botUsername ?: ""),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ChatStep(state: OnboardingViewModel.UiState, viewModel: OnboardingViewModel) {
    val ctx = LocalContext.current
    Text(ctx.getString(R.string.onboarding_chat_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text(ctx.getString(R.string.onboarding_chat_body))
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { viewModel.detectChat() },
        enabled = !state.detecting
    ) {
        if (state.detecting) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(ctx.getString(R.string.onboarding_detect_chat))
    }
    if (state.chatError == "no_message") {
        Spacer(Modifier.height(8.dp))
        Text(ctx.getString(R.string.onboarding_no_message), color = MaterialTheme.colorScheme.error)
    } else if (state.chatError != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            ctx.getString(R.string.onboarding_connection_error, state.chatError),
            color = MaterialTheme.colorScheme.error
        )
    }
    if (!state.chatId.isNullOrBlank()) {
        Spacer(Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(state.chatTitle ?: state.chatId, style = MaterialTheme.typography.titleMedium)
                Text("Chat ID: ${state.chatId}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { viewModel.finish() }, modifier = Modifier.fillMaxWidth()) {
            Text(ctx.getString(R.string.onboarding_finish))
        }
    }
}
