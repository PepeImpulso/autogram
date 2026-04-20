package com.autogram.app.ui.phone

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autogram.app.domain.model.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Circular avatar used on the main screen and in the manage-pinned list.
 *
 * Priority:
 *   1. [Conversation.photoLocalPath] if set — decoded off the main thread.
 *   2. Coloured circle with the first letter of the title, deterministic per id.
 */
@Composable
fun CircleAvatar(
    conv: Conversation,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, conv.photoLocalPath) {
        val path = conv.photoLocalPath
        value = if (path != null && File(path).exists()) {
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
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

// Stable pastel colour derived from the conversation id so each circle keeps
// the same tint across launches even when the user has no photo set.
private fun colorFor(conv: Conversation): Color {
    val palette = listOf(
        Color(0xFF5E81AC), Color(0xFFBF616A), Color(0xFFA3BE8C),
        Color(0xFFD08770), Color(0xFFB48EAD), Color(0xFF88C0D0),
        Color(0xFFEBCB8B), Color(0xFF8FBCBB)
    )
    val idx = abs(conv.id.hashCode()) % palette.size
    return palette[idx]
}
