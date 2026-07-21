package com.autogram.app.ui.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.autogram.app.R
import com.autogram.app.infrastructure.telegram.mtproto.TelegramReportOption

@Composable
fun TelegramReportDialogs(
    state: TelegramReportUiState?,
    onChoose: (TelegramReportOption) -> Unit,
    onSubmitDetails: (String) -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        null -> Unit
        TelegramReportUiState.Loading -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.moderation_report_progress)) },
            text = { CircularProgressIndicator() },
            confirmButton = {}
        )
        is TelegramReportUiState.ChooseOption -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(state.title.ifBlank { stringResource(R.string.moderation_report_title) }) },
            text = {
                Column {
                    state.options.forEach { option ->
                        TextButton(
                            onClick = { onChoose(option) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(option.label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        )
        is TelegramReportUiState.EnterDetails -> {
            var details by remember(state) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.moderation_report_details_title)) },
                text = {
                    OutlinedTextField(
                        value = details,
                        onValueChange = { if (it.length <= 1024) details = it },
                        label = { Text(stringResource(R.string.moderation_report_details_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { onSubmitDetails(details) },
                        enabled = state.optional || details.isNotBlank()
                    ) { Text(stringResource(R.string.moderation_report_submit)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                }
            )
        }
        TelegramReportUiState.ChooseMessage -> ModerationNoticeDialog(
            message = stringResource(R.string.moderation_report_choose_message),
            onDismiss = onDismiss
        )
        TelegramReportUiState.Submitted -> ModerationNoticeDialog(
            message = stringResource(R.string.moderation_report_success),
            onDismiss = onDismiss
        )
        is TelegramReportUiState.Failed -> ModerationNoticeDialog(
            message = stringResource(R.string.moderation_report_failed, state.message),
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun ModerationNoticeDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.moderation_report_title)) },
        text = { Text(message, modifier = Modifier.padding(top = 4.dp)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        }
    )
}
