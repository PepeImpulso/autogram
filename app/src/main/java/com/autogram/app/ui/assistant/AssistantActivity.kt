package com.autogram.app.ui.assistant

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.autogram.app.R
import com.autogram.app.ui.phone.BaseLocaleActivity
import com.autogram.app.ui.theme.AutogramTheme

/**
 * v1.22.0 / issue #63 — Phase 1 entry point for Android's digital
 * assistant role. Declares `android.intent.action.ASSIST` in the
 * manifest so Autogram appears as a candidate in Samsung / Android
 * "Default digital assistant app" pickers. When the user invokes the
 * assistant via the system gesture / button, this Activity opens.
 *
 * Phase 1 only: verifies registration. The voice-loop, backend
 * integration, screen-context capture and action wiring all come in
 * later phases. The placeholder UI tells the user the feature is being
 * built and lets them dismiss the screen so the OS doesn't think
 * Autogram is misbehaving.
 */
class AssistantActivity : BaseLocaleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutogramTheme {
                AssistantPlaceholderScreen(onDismiss = { finish() })
            }
        }
    }
}

@Composable
private fun AssistantPlaceholderScreen(onDismiss: () -> Unit) {
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
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp)
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.assistant_placeholder_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.assistant_placeholder_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.assistant_placeholder_dismiss))
            }
        }
    }
}
