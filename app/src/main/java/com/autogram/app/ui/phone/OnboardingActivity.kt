package com.autogram.app.ui.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.autogram.app.R
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.ui.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Four-step first-run wizard that walks the user through recording, primary
 * target, quick shortcuts and settings. Sets `settings.onboardingCompleted`
 * once the user reaches the end or skips. MainActivity.onResume decides
 * whether to launch this activity based on that flag.
 */
@AndroidEntryPoint
class OnboardingActivity : BaseLocaleActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutogramTheme {
                OnboardingScreen(
                    onFinish = { finishOnboarding() },
                    onChooseTarget = {
                        startActivity(Intent(this, TargetPickerActivity::class.java))
                    }
                )
            }
        }
    }

    private fun finishOnboarding() {
        lifecycleScope.launch {
            val s = settingsRepository.get()
            settingsRepository.update(s.copy(onboardingCompleted = true))
            finish()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingScreen(
    onFinish: () -> Unit,
    onChooseTarget: () -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    val steps = 4
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    if (step < steps - 1) {
                        TextButton(onClick = onFinish) {
                            Text(stringResource(R.string.onboarding_skip))
                        }
                    }
                }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (step > 0) {
                    TextButton(onClick = { step-- }) {
                        Text(stringResource(R.string.onboarding_back))
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                StepIndicator(current = step, total = steps)
                Button(onClick = {
                    if (step < steps - 1) step++ else onFinish()
                }) {
                    Text(
                        if (step < steps - 1) stringResource(R.string.onboarding_next)
                        else stringResource(R.string.onboarding_finish)
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 24.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (step) {
                0 -> StepPane(
                    icon = Icons.Filled.Stars,
                    title = stringResource(R.string.onboarding_welcome_title),
                    body = stringResource(R.string.onboarding_welcome_body)
                )
                1 -> StepPane(
                    icon = Icons.Filled.Mic,
                    title = stringResource(R.string.onboarding_record_title),
                    body = stringResource(R.string.onboarding_record_body)
                )
                2 -> StepPane(
                    icon = Icons.Filled.Flag,
                    title = stringResource(R.string.onboarding_target_title),
                    body = stringResource(R.string.onboarding_target_body)
                ) {
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = onChooseTarget) {
                        Text(stringResource(R.string.onboarding_target_cta))
                    }
                }
                3 -> StepPane(
                    icon = Icons.Filled.Star,
                    title = stringResource(R.string.onboarding_shortcuts_title),
                    body = stringResource(R.string.onboarding_shortcuts_body)
                )
            }
        }
    }
}

@Composable
private fun StepPane(
    icon: ImageVector,
    title: String,
    body: String,
    extra: @Composable ColumnScope.() -> Unit = {}
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(96.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(44.dp)
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        extra()
    }
}

@Composable
private fun StepIndicator(current: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { i ->
            Surface(
                shape = CircleShape,
                color = if (i == current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (i == current) 10.dp else 8.dp)
            ) {}
        }
    }
}
