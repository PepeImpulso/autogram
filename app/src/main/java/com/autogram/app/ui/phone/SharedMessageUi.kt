package com.autogram.app.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.autogram.app.R
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.displayText
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
/**
 * v1.23.0-rc.6 — formats a duration in milliseconds as `m:ss`. Shared
 * by [MessageRow]'s elapsed/total readout and by the Now Playing panel
 * in PendingQueueScreen.
 */
internal fun formatDurationMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

@Composable
internal fun MessageRow(
    message: Message,
    conversation: Conversation?,
    isPlayingThis: Boolean,
    isPausedThis: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    /**
     * v1.23.0-rc.6 — when the row is the active playing one, polled at
     * ~250 ms to surface a `0:15 / 1:30` readout next to the arrival
     * time and to drive the inline waveform's progress. Both providers
     * may return null (TTS playback, MediaPlayer not yet loaded) — in
     * that case the readout / waveform are suppressed for this frame.
     */
    positionMsProvider: () -> Int? = { null },
    durationMsProvider: () -> Int? = { null },
    /**
     * v1.23.0-rc.7 — tap or horizontal-drag on the row's inline waveform
     * (only present when this row is the active playing one) jumps
     * playback to the chosen position. Defaults to a no-op so older
     * call sites still compile.
     */
    onSeek: (Int) -> Unit = {},
    onReport: (() -> Unit)? = null
) {
    val isOutgoing = message.direction == MessageDirection.OUTGOING
    val hasAudio = !message.audioFilePath.isNullOrBlank()
    val showPlayButton = !isOutgoing && (hasAudio || hasReadableText(message))

    // v1.23.0-rc.7 — playback position state hoisted above the inner Row
    // so both the trailing time column and the inline waveform see the
    // same numbers each frame. Polled at ~250 ms only while this row is
    // the active playback target.
    var positionMs by remember(message.id) { mutableIntStateOf(positionMsProvider() ?: 0) }
    var durationMs by remember(message.id) { mutableIntStateOf(durationMsProvider() ?: 0) }
    LaunchedEffect(message.id, isPlayingThis) {
        while (isPlayingThis) {
            positionMs = positionMsProvider() ?: positionMs
            durationMs = durationMsProvider() ?: durationMs
            delay(250)
        }
    }
    val totalMs = (message.audioDurationMs ?: durationMs.toLong()).coerceAtLeast(0L)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(
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
                // v1.23.0-rc.7 — live `0:15 / 1:30` readout right under
                // the arrival time. Pre-rc.7 this lived under the
                // subtitle, but a 2-line subtitle pushed it out of view
                // because the row's right column (with the play button)
                // dictated the row height. Co-locating with the arrival
                // time keeps both clock-style readouts visually grouped
                // and out of the subtitle's wrap area.
                if (isPlayingThis && hasAudio) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${formatDurationMs(positionMs.toLong())} / ${formatDurationMs(totalMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
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
                if (onReport != null) {
                    IconButton(
                        onClick = onReport,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Filled.Flag,
                            contentDescription = stringResource(R.string.moderation_report_action),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }
        // v1.23.0-rc.7 — inline waveform under the active row. Tappable
        // for tap-to-seek and draggable for scrubbing — same gestures as
        // the Pending Queue's Now Playing panel waveform but at row level
        // so the user can scrub directly from History without opening a
        // separate Now Playing surface. Only renders when this row is
        // the active playing target AND has audio (text-only rows have
        // nothing useful to draw).
        if (isPlayingThis && hasAudio) {
            var waveformWidthPx by remember(message.id) { mutableIntStateOf(0) }
            val seekFromX: (Float) -> Unit = { x ->
                val width = waveformWidthPx
                val total = if (durationMs > 0) durationMs else totalMs.toInt()
                if (width > 0 && total > 0) {
                    val fraction = (x / width.toFloat()).coerceIn(0f, 1f)
                    val target = (fraction * total).toInt()
                    // Update local position immediately so the waveform
                    // doesn't briefly show the old value before the
                    // 250 ms poller catches up to the seek.
                    positionMs = target
                    onSeek(target)
                }
            }
            Spacer(Modifier.height(8.dp))
            Waveform(
                messageId = message.id,
                progress = if (durationMs > 0) {
                    (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                } else if (totalMs > 0) {
                    (positionMs.toFloat() / totalMs).coerceIn(0f, 1f)
                } else 0f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .onSizeChanged { size: IntSize -> waveformWidthPx = size.width }
                    .pointerInput(message.id, durationMs) {
                        detectTapGestures(onTap = { offset -> seekFromX(offset.x) })
                    }
                    .pointerInput(message.id, durationMs) {
                        detectHorizontalDragGestures { change, _ ->
                            seekFromX(change.position.x)
                        }
                    }
            )
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
    val text = message.displayText()
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
