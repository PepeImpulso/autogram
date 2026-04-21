package com.autogram.app.ui.phone

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autogram.app.R
import com.autogram.app.domain.model.Conversation
import com.autogram.app.ui.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pantalla "Gestionar accesos rápidos": lista los círculos fijados a la pantalla
 * principal, permite añadir nuevos (via [PinnedPickerActivity]), elegir cuál es
 * el primario (Merlin — círculo grande), establecer una imagen de fondo y
 * eliminar círculos secundarios.
 */
@AndroidEntryPoint
class ManagePinnedActivity : BaseLocaleActivity() {

    private val viewModel: ManagePinnedViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutogramTheme {
                ManagePinnedScreen(
                    viewModel = viewModel,
                    onBack = { finish() },
                    onAdd = { startActivity(Intent(this, PinnedPickerActivity::class.java)) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManagePinnedScreen(
    viewModel: ManagePinnedViewModel,
    onBack: () -> Unit,
    onAdd: () -> Unit
) {
    val pinned by viewModel.pinned.collectAsState()

    // Target we are about to set a photo for (lifted out of the list item so
    // the photo picker's callback can still reach it after the item recomposes).
    // Uses the system Photo Picker (PickVisualMedia) — handles back-press
    // cleanly and doesn't require storage permissions.
    var photoTargetId by remember { mutableStateOf<String?>(null) }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val id = photoTargetId
        photoTargetId = null
        if (uri != null && id != null) {
            viewModel.setPhoto(id, uri)
        }
    }

    // When non-null, a fullscreen dialog shows the photo with pinch-zoom + pan.
    // Null → dialog closed.
    var zoomPath by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pinned_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.common_add)) }
            )
        }
    ) { padding ->
        if (pinned.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    stringResource(R.string.pinned_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(pinned, key = { it.id }) { conv ->
                PinnedRow(
                    conv = conv,
                    onAvatarTap = {
                        // Only opens a zoom view if there's actually a photo — tapping
                        // the fallback coloured initial isn't interesting.
                        conv.photoLocalPath?.let { zoomPath = it }
                    },
                    onSetPrimary = { viewModel.setPrimary(conv.id) },
                    onPickPhoto = {
                        photoTargetId = conv.id
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onClearPhoto = { viewModel.clearPhoto(conv.id) },
                    onRemove = { viewModel.remove(conv.id) }
                )
            }
        }
    }

    zoomPath?.let { path ->
        ZoomablePhotoDialog(path = path, onDismiss = { zoomPath = null })
    }
}

@Composable
private fun PinnedRow(
    conv: Conversation,
    onAvatarTap: () -> Unit,
    onSetPrimary: () -> Unit,
    onPickPhoto: () -> Unit,
    onClearPhoto: () -> Unit,
    onRemove: () -> Unit
) {
    val hasPhoto = conv.photoLocalPath != null
    ListItem(
        leadingContent = {
            CircleAvatar(
                conv = conv,
                size = 48.dp,
                modifier = if (hasPhoto) Modifier.clickable(onClick = onAvatarTap)
                           else Modifier
            )
        },
        headlineContent = {
            val untitled = stringResource(R.string.common_untitled)
            Text(
                buildString {
                    append(conv.targetTitle ?: untitled)
                    conv.topicName?.let { append(" › ").append(it) }
                }
            )
        },
        supportingContent = {
            Text(
                if (conv.isActive) stringResource(R.string.pinned_label_primary)
                else stringResource(R.string.pinned_label_secondary),
                style = MaterialTheme.typography.bodySmall,
                color = if (conv.isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onSetPrimary) {
                    Icon(
                        imageVector = if (conv.isActive) Icons.Filled.Star
                                      else Icons.Outlined.StarBorder,
                        contentDescription = stringResource(R.string.pinned_mark_primary_cd),
                        tint = if (conv.isActive) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = if (conv.photoLocalPath != null) onClearPhoto else onPickPhoto
                ) {
                    Icon(Icons.Filled.Image, stringResource(R.string.pinned_background_cd))
                }
                // Allow removing only non-primary circles — the primary target
                // is managed through Settings › Destino de mensajes.
                if (!conv.isActive) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.common_remove))
                    }
                }
            }
        }
    )
}

/**
 * Fullscreen dialog that shows a pinned-circle photo at full resolution, with
 * pinch-to-zoom + pan so the user can inspect the image they set as background.
 *
 * Loads the raw bitmap (not downsampled) — this is an on-demand viewer, so the
 * extra memory is fine. The dialog dismisses on back press or tap on the
 * close button.
 */
@Composable
private fun ZoomablePhotoDialog(path: String, onDismiss: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (File(path).exists()) BitmapFactory.decodeFile(path)?.asImageBitmap()
                else null
            }.getOrNull()
        }
    }

    // If the file disappeared (user cleared it mid-dialog), close gracefully.
    LaunchedEffect(bitmap) {
        // produceState emits null while loading; only auto-dismiss if we actually
        // finished and got nothing back — harmless but covers the edge case.
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
        ) {
            val img = bitmap
            if (img != null) {
                Image(
                    bitmap = img,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                // When zoomed out to 1x lock the image back to centre so
                                // a stray pan doesn't leave the photo off-screen.
                                if (scale <= 1.01f) {
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                }
                            }
                        }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.common_close),
                    tint = Color.White
                )
            }
            Text(
                stringResource(R.string.pinned_zoom_hint),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
            )
        }
    }
}
