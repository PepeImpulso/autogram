package com.autogram.app.ui.phone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.autogram.app.R

@Composable
fun UpdateDialog(
    state: UpdateViewModel.State.Available,
    currentVersion: String,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val sizeKb = if (state.info.apkSizeBytes > 0) (state.info.apkSizeBytes / 1024).toInt() else 0
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null) },
        title = { Text(ctx.getString(R.string.update_dialog_title)) },
        text = {
            // Cap the height first, then scroll inside it. The other order
            // capped the content itself, so long notes were cut off with
            // nothing to scroll.
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    ctx.getString(
                        R.string.update_dialog_body,
                        state.info.latestVersion,
                        currentVersion,
                        sizeKb
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                val notes = state.info.releaseNotes
                if (!notes.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        ctx.getString(R.string.update_dialog_release_notes),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            readableReleaseNotes(notes),
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onInstall) {
                Text(ctx.getString(R.string.update_dialog_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(ctx.getString(R.string.update_dialog_later))
            }
        }
    )
}

/**
 * Release notes are Markdown written for GitHub, hard-wrapped at ~75
 * columns. Shown as-is in the dialog they broke mid-sentence and showed
 * `#`, `**` and `-` markers. Joins wrapped lines back into paragraphs and
 * turns headings and bullets into plain text.
 */
internal fun readableReleaseNotes(markdown: String): String {
    val paragraphs = mutableListOf<String>()
    var current = StringBuilder()
    fun flush() {
        if (current.isNotBlank()) paragraphs += current.toString()
        current = StringBuilder()
    }
    for (raw in markdown.lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> flush()
            line.startsWith("#") -> {
                flush()
                current.append(line.trimStart('#').trim())
                flush()
            }
            line.startsWith("- ") || line.startsWith("* ") || line.matches(Regex("\\d+\\.\\s.*")) -> {
                flush()
                current.append(if (line[0].isDigit()) line else "• " + line.drop(2))
            }
            else -> {
                if (current.isNotEmpty()) current.append(' ')
                current.append(line)
            }
        }
    }
    flush()
    return paragraphs.joinToString("\n\n")
        .replace("**", "")
        .replace("`", "")
}
