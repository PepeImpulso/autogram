package com.autogram.app.ui.phone

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import kotlin.math.max
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autogram.app.R
import com.autogram.app.domain.model.CircleFolder
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
    val folders by viewModel.folders.collectAsState()
    var showAddFolderDialog by remember { mutableStateOf(false) }
    var folderPendingDelete by remember { mutableStateOf<CircleFolder?>(null) }

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
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.pinned_title),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "folders_section") {
                FoldersSection(
                    folders = folders,
                    onAdd = { showAddFolderDialog = true },
                    onDeleteRequest = { folderPendingDelete = it }
                )
            }
            val secondary = pinned.filter { !it.isActive }
            items(pinned, key = { it.id }) { conv ->
                val secondaryIdx = if (conv.isActive) -1 else secondary.indexOfFirst { it.id == conv.id }
                PinnedRow(
                    conv = conv,
                    folders = folders,
                    canMoveUp = secondaryIdx > 0,
                    canMoveDown = secondaryIdx in 0 until secondary.lastIndex,
                    onAvatarTap = {
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
                    onRemove = { viewModel.remove(conv.id) },
                    onMoveUp = { viewModel.moveUp(conv.id) },
                    onMoveDown = { viewModel.moveDown(conv.id) },
                    onAssignFolder = { folderId -> viewModel.assignFolder(conv.id, folderId) }
                )
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (showAddFolderDialog) {
        FolderNameDialog(
            initial = "",
            title = stringResource(R.string.pinned_folder_add),
            onConfirm = { name ->
                showAddFolderDialog = false
                viewModel.createFolder(name)
            },
            onDismiss = { showAddFolderDialog = false }
        )
    }
    folderPendingDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderPendingDelete = null },
            icon = { Icon(Icons.Filled.FolderOff, null) },
            title = { Text(folder.name) },
            text = { Text(stringResource(R.string.pinned_folder_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFolder(folder.id)
                    folderPendingDelete = null
                }) { Text(stringResource(R.string.pinned_folder_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { folderPendingDelete = null }) {
                    Text(stringResource(R.string.backup_cancel))
                }
            }
        )
    }

    zoomPath?.let { path ->
        ZoomablePhotoDialog(path = path, onDismiss = { zoomPath = null })
    }
}

@Composable
private fun PinnedRow(
    conv: Conversation,
    folders: List<CircleFolder>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onAvatarTap: () -> Unit,
    onSetPrimary: () -> Unit,
    onPickPhoto: () -> Unit,
    onClearPhoto: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onAssignFolder: (String?) -> Unit
) {
    val hasPhoto = conv.photoLocalPath != null
    val untitled = stringResource(R.string.common_untitled)
    var menuOpen by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (conv.isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                             else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar — larger than the previous 48dp ListItem, with the
            // primary badge overlaid in the bottom-right.
            Box(contentAlignment = Alignment.BottomEnd) {
                CircleAvatar(
                    conv = conv,
                    size = 56.dp,
                    modifier = if (hasPhoto) Modifier.clickable(onClick = onAvatarTap) else Modifier
                )
                if (conv.isActive) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    conv.targetTitle ?: untitled,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                conv.topicName?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(6.dp))
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = {
                        Text(
                            if (conv.isActive) stringResource(R.string.pinned_label_primary)
                            else stringResource(R.string.pinned_label_secondary),
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledContainerColor = if (conv.isActive)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                        disabledLabelColor = if (conv.isActive)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
            // Reorder chevrons on the right — only for secondary circles,
            // disabled at range ends. Smaller than the old IconButtons so
            // they don't dominate the card.
            if (!conv.isActive) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = onMoveUp,
                        enabled = canMoveUp,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Filled.ArrowUpward,
                            stringResource(R.string.pinned_move_up),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = canMoveDown,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Filled.ArrowDownward,
                            stringResource(R.string.pinned_move_down),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            // Overflow menu: collapses the rarely-used actions (set primary,
            // photo management, delete) so the card stays scannable.
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.pinned_more_actions),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    if (!conv.isActive) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pinned_mark_primary)) },
                            onClick = { menuOpen = false; onSetPrimary() },
                            leadingIcon = { Icon(Icons.Filled.StarBorder, null) }
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (hasPhoto) stringResource(R.string.pinned_clear_photo)
                                else stringResource(R.string.pinned_set_photo)
                            )
                        },
                        onClick = {
                            menuOpen = false
                            if (hasPhoto) onClearPhoto() else onPickPhoto()
                        },
                        leadingIcon = {
                            Icon(
                                if (hasPhoto) Icons.Filled.HideImage else Icons.Filled.Image,
                                null
                            )
                        }
                    )
                    // Folder assignment — shown only when the user has at
                    // least one folder. Radio-style sub-items including
                    // "No folder" so unassigning stays one tap away.
                    if (folders.isNotEmpty()) {
                        androidx.compose.material3.HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pinned_folder_none)) },
                            onClick = { menuOpen = false; onAssignFolder(null) },
                            leadingIcon = {
                                Icon(
                                    if (conv.folderId == null) Icons.Filled.Star else Icons.Filled.FolderOff,
                                    null,
                                    tint = if (conv.folderId == null) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                        folders.forEach { folder ->
                            DropdownMenuItem(
                                text = { Text(folder.name) },
                                onClick = { menuOpen = false; onAssignFolder(folder.id) },
                                leadingIcon = {
                                    Icon(
                                        if (conv.folderId == folder.id) Icons.Filled.Star else Icons.Filled.Folder,
                                        null,
                                        tint = if (conv.folderId == folder.id) MaterialTheme.colorScheme.primary
                                               else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            )
                        }
                    }
                    if (!conv.isActive) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.common_remove),
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = { menuOpen = false; onRemove() },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Delete,
                                    null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Fullscreen dialog that shows a pinned-circle photo with pinch-to-zoom + pan.
 *
 * Loads the bitmap **sampled** to at most 2 × screen width. A user's gallery
 * photo can easily be 12-24 MP (50-100 MB decoded as ARGB_8888), which OOMs on
 * mid-range devices the moment the dialog opens. 2× screen still gives plenty
 * of room for the 6× pinch-zoom ceiling enforced below without the pixels
 * looking blocky — for a 1440-wide display that's 2880px of resolution,
 * comfortably beyond anything the user can actually perceive.
 */
@Composable
private fun ZoomablePhotoDialog(path: String, onDismiss: () -> Unit) {
    val targetPx = with(androidx.compose.ui.platform.LocalConfiguration.current) {
        (screenWidthDp * 2).coerceAtLeast(512)
    }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path, targetPx) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (!File(path).exists()) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                val srcLong = max(bounds.outWidth, bounds.outHeight)
                if (srcLong <= 0) return@runCatching null
                var sample = 1
                while (srcLong / sample > targetPx * 2) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
            }.getOrNull()
        }
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

/**
 * Horizontal folder chip row. One FilterChip per folder, plus a "New folder"
 * outlined chip at the end. Long-press a folder to delete it (via callback
 * that the parent hoists into an AlertDialog so the confirm lives outside
 * this composable).
 */
@Composable
private fun FoldersSection(
    folders: List<CircleFolder>,
    onAdd: () -> Unit,
    onDeleteRequest: (CircleFolder) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 10.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.pinned_folder_section),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.pinned_folder_section_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(folders, key = { it.id }) { folder ->
                    FolderChip(folder = folder, onLongPress = { onDeleteRequest(folder) })
                }
                item(key = "__add") {
                    FilterChip(
                        selected = false,
                        onClick = onAdd,
                        label = { Text(stringResource(R.string.pinned_folder_add)) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.CreateNewFolder,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderChip(folder: CircleFolder, onLongPress: () -> Unit) {
    // Long-press to delete; single tap is a no-op here (the folder tabs on
    // MainActivity handle selection, this screen is CRUD only). Uses a
    // combinedClickable so the AssistChip stays accessible.
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.pointerInput(folder.id) {
            detectTapGestures(onLongPress = { onLongPress() })
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                folder.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun FolderNameDialog(
    initial: String,
    title: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.CreateNewFolder, null) },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.pinned_folder_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text) },
                enabled = text.isNotBlank()
            ) { Text(stringResource(R.string.common_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) }
        }
    )
}
