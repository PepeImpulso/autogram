package com.autogram.app.ui.phone

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.absoluteValue
import kotlin.random.Random

/**
 * Decorative pseudo-waveform for voice messages. Real amplitude
 * extraction from .ogg files is heavy and not worth the complexity for
 * a UI affordance, so we render a deterministic random waveform seeded
 * by the message id — same id always renders the same shape, different
 * messages render visibly different shapes. The progress overlay paints
 * bars up to [progress] in [playedColor]; the rest in [unplayedColor].
 *
 * For TTS playback there's no audio position to sample, so the caller
 * passes progress = 0f and the bar stays "unplayed" the whole time.
 */
@Composable
fun Waveform(
    messageId: String,
    progress: Float,
    modifier: Modifier = Modifier,
    barCount: Int = 48,
    playedColor: Color = MaterialTheme.colorScheme.tertiary,
    unplayedColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
) {
    Canvas(modifier = modifier) {
        drawWaveform(messageId, progress, barCount, playedColor, unplayedColor)
    }
}

private fun DrawScope.drawWaveform(
    messageId: String,
    progress: Float,
    barCount: Int,
    playedColor: Color,
    unplayedColor: Color
) {
    val gap = 3f
    val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(1f)
    val rng = Random(messageId.hashCode().absoluteValue.toLong())
    val amplitudes = FloatArray(barCount) { 0.25f + rng.nextFloat() * 0.75f }
    val playedUntil = (progress * barCount).toInt()
    for (i in 0 until barCount) {
        val barHeight = size.height * amplitudes[i]
        val x = i * (barWidth + gap)
        val y = (size.height - barHeight) / 2f
        drawRect(
            color = if (i <= playedUntil && progress > 0f) playedColor else unplayedColor,
            topLeft = Offset(x, y),
            size = Size(barWidth, barHeight)
        )
    }
}
