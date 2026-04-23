package com.autogram.app.ui.phone

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autogram.app.domain.model.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Circular avatar used on the main screen and in the manage-pinned list.
 *
 * Priority:
 *   1. [Conversation.photoLocalPath] if set — decoded off the main thread.
 *   2. Coloured circle with the first letter of the title, deterministic per id.
 */
@Composable
@SuppressLint("ProduceStateDoesNotAssignValue")
fun CircleAvatar(
    conv: Conversation,
    size: Dp,
    modifier: Modifier = Modifier
) {
    // Target pixel size for the decoded bitmap — ~2x the on-screen size so
    // cropping to the circle still looks sharp on high-density displays, but
    // far below the raw gallery resolution that could OOM if loaded verbatim.
    val targetPx = with(LocalDensity.current) { (size * 2).toPx().toInt().coerceAtLeast(64) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, conv.photoLocalPath, targetPx) {
        val path = conv.photoLocalPath
        value = if (path != null && File(path).exists()) {
            withContext(Dispatchers.IO) {
                runCatching { decodeSampled(path, targetPx)?.asImageBitmap() }.getOrNull()
            }
        } else null
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colorFor(conv)),
        contentAlignment = Alignment.Center
    ) {
        val img = bitmap
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = conv.targetTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape)
            )
        } else {
            val label = (conv.topicName ?: conv.targetTitle ?: "?")
                .trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            Text(
                label,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp
            )
        }
    }
}

// Two-pass decode: first read bounds only, pick inSampleSize so the longer side
// is at or just above [targetPx], then decode. Keeps memory bounded for large
// gallery photos without us having to hard-code a max resolution.
private fun decodeSampled(path: String, targetPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val srcLong = max(bounds.outWidth, bounds.outHeight)
    if (srcLong <= 0) return null
    var sample = 1
    while (srcLong / sample > targetPx * 2) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeFile(path, opts)
}

// Stable pastel colour derived from the conversation id so each circle keeps
// the same tint across launches even when the user has no photo set.
private fun colorFor(conv: Conversation): Color {
    val palette = listOf(
        Color(0xFF5E81AC), Color(0xFFBF616A), Color(0xFFA3BE8C),
        Color(0xFFD08770), Color(0xFFB48EAD), Color(0xFF88C0D0),
        Color(0xFFEBCB8B), Color(0xFF8FBCBB)
    )
    // `abs(Int.MIN_VALUE) == Int.MIN_VALUE` (two's-complement overflow), and
    // `MIN_VALUE % N` can return a NEGATIVE remainder when N doesn't divide
    // MIN_VALUE evenly → IndexOutOfBoundsException on the palette lookup.
    // With 8 entries we happen to be safe by accident (MIN_VALUE is a
    // multiple of 8), but any future palette resize would reintroduce the
    // crash. Mask off the sign bit instead of using `abs`.
    val idx = (conv.id.hashCode() and Int.MAX_VALUE) % palette.size
    return palette[idx]
}
