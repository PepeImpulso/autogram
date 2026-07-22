package com.autogram.app.ui.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autogram.app.BuildConfig
import com.autogram.app.R
import com.autogram.app.infrastructure.privacy.AcceptableUseConsentStore
import com.autogram.app.ui.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Mandatory, non-skippable acceptance gate before Autogram can send UGC. */
@AndroidEntryPoint
class AcceptableUseActivity : BaseLocaleActivity() {
    @Inject lateinit var consentStore: AcceptableUseConsentStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutogramTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AcceptableUseScreen(
                        onAccept = {
                            consentStore.accept()
                            finish()
                        },
                        onExit = { finishAffinity() }
                    )
                }
            }
        }
    }
}

@Composable
internal fun AcceptableUseScreen(onAccept: () -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    var adultConfirmed by remember { mutableStateOf(false) }
    var rulesConfirmed by remember { mutableStateOf(false) }
    BackHandler(onBack = onExit)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            stringResource(R.string.acceptable_use_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.acceptable_use_intro))
        Spacer(Modifier.height(16.dp))
        Text("• ${stringResource(R.string.acceptable_use_rule_content)}")
        Spacer(Modifier.height(8.dp))
        Text("• ${stringResource(R.string.acceptable_use_rule_moderation)}")
        Spacer(Modifier.height(8.dp))
        Text("• ${stringResource(R.string.acceptable_use_rule_telegram)}")
        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = { openUrl(context, BuildConfig.ACCEPTABLE_USE_URL) },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.acceptable_use_open_terms)) }
        OutlinedButton(
            onClick = { openUrl(context, "https://telegram.org/tos") },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.acceptable_use_open_telegram_terms)) }
        Spacer(Modifier.height(12.dp))
        ConsentCheckbox(
            checked = adultConfirmed,
            label = stringResource(R.string.acceptable_use_adult_confirm),
            onCheckedChange = { adultConfirmed = it }
        )
        ConsentCheckbox(
            checked = rulesConfirmed,
            label = stringResource(R.string.acceptable_use_rules_confirm),
            onCheckedChange = { rulesConfirmed = it }
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onAccept,
            enabled = adultConfirmed && rulesConfirmed,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.acceptable_use_accept)) }
        OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.acceptable_use_exit))
        }
    }
}

@Composable
private fun ConsentCheckbox(
    checked: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, modifier = Modifier.weight(1f))
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
}
