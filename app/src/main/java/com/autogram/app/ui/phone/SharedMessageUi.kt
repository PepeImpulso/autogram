package com.autogram.app.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autogram.app.R
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.model.MessageDirection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.22.0 — unified message row used by both the History and Pending
 * tabs. Avatar on the left (matching the Now Playing drawer's visual
 * language), conversation title + subtitle in the middle, and a
 * trailing play/pause button when the row is playable. Outgoing
 * messages share the same row layout but get a "Sent: …" prefix and
 * no play button (we don't replay user's own recordings).
 */
@Composable
internal fun MessageRow(
    message: Message,
    conversation: Conversation?,
    isPlayingThis: Boolean,
    isPausedThis: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit
) {
    val isOutgoing = message.direction == MessageDirection.OUTGOING
    val hasAudio = !message.audioFilePath.isNullOrBlank()
    val showPlayButton = !isOutgoing && (hasAudio || hasReadableText(message))
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ConversationAvatar(conversation = conversation, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversationLabel(conversation),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitleFor(message, isOutgoing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = ROW_TIME_FORMAT.format(Date(message.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (showPlayButton) {
                    Spacer(Modifier.height(6.dp))
                    FilledIconButton(
                        onClick = when {
                            isPlayingThis -> onPause
                            isPausedThis -> onResume
                            else -> onPlay
                        },
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isPlayingThis)
                                MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.primary,
                            contentColor = if (isPlayingThis)
                                MaterialTheme.colorScheme.onTertiary
                            else MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = if (isPlayingThis) Icons.Filled.Pause
                                          else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlayingThis)
                                stringResource(R.string.playback_pause_cd)
                            else stringResource(R.string.playback_play_cd),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationAvatar(conversation: Conversation?, size: androidx.compose.ui.unit.Dp) {
    if (conversation != null) {
        CircleAvatar(conv = conversation, size = size)
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(size * 0.5f)
            )
        }
    }
}

@Composable
private fun conversationLabel(conversation: Conversation?): String {
    val raw = conversation?.let {
        it.topicName?.takeIf { t -> t.isNotBlank() } ?: it.targetTitle?.takeIf { t -> t.isNotBlank() }
    }
    return raw ?: stringResource(R.string.main_default_primary_name)
}

@Composable
private fun subtitleFor(message: Message, isOutgoing: Boolean): String {
    val hasAudio = !message.audioFilePath.isNullOrBlank()
    val text = message.summary?.takeIf { it.isNotBlank() }
        ?: message.transcription?.takeIf { it.isNotBlank() }
        ?: message.textContent?.takeIf { it.isNotBlank() }
    val core = when {
        hasAudio && text != null -> text
        hasAudio -> stringResource(R.string.pending_queue_row_voice, formatRowDuration(message.audioDurationMs ?: 0L))
        text != null -> text
        else -> stringResource(R.string.pending_queue_row_text_generic)
    }
    return if (isOutgoing) stringResource(R.string.history_row_sent_prefix, core) else core
}

private fun hasReadableText(message: Message): Boolean {
    return !message.summary.isNullOrBlank() ||
            !message.transcription.isNullOrBlank() ||
            !message.textContent.isNullOrBlank()
}

private fun formatRowDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

private val ROW_TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * Horizontal scrollable chip row for picking which contact to filter by
 * (History/Pending) or send to (assistant target picker, v1.23.0).
 *
 * @param includeAllChip when true, prepends an "All" chip mapped to
 *   `null`. History/Pending pass true so the user can clear the filter;
 *   the assistant passes false because every assistant turn must
 *   resolve to a specific contact.
 */
@Composable
internal fun ContactFilterRow(
    pinned: List<Conversation>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    includeAllChip: Boolean = true
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (includeAllChip) {
            item {
                FilterChipPill(
                    label = stringResource(R.string.contact_filter_all),
                    selected = selectedId == null,
                    onClick = { onSelect(null) }
                )
            }
        }
        items(pinned, key = { it.id }) { conv ->
            FilterChipPill(
                label = conv.topicName?.takeIf { it.isNotBlank() }
                    ?: conv.targetTitle?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.main_default_primary_name),
                selected = selectedId == conv.id,
                onClick = { onSelect(conv.id) }
            )
        }
    }
}

@Composable
private fun FilterChipPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                       else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .height(32.dp)
            .defaultMinSize(minWidth = 56.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
