package com.autogram.app.ui.phone

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autogram.app.R
import com.autogram.app.infrastructure.telegram.mtproto.ChatKind
import com.autogram.app.infrastructure.telegram.mtproto.ChatSummary
import com.autogram.app.infrastructure.telegram.mtproto.TopicSummary
import com.autogram.app.ui.theme.AutogramTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Flow for pinning secondary targets (circles on the main screen):
 *   Step 1 — choose a chat.
 *   Step 2 — if the chat is a forum supergroup, multi-select topics with
 *            checkboxes and confirm; otherwise the chat itself is pinned.
 *
 * Separate from [TargetPickerActivity] which picks the single *primary* target.
 */
@AndroidEntryPoint
class PinnedPickerActivity : BaseLocaleActivity() {

    private val viewModel: PinnedPickerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutogramTheme {
                PinnedPickerScreen(
                    viewModel = viewModel,
                    onFinish = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinnedPickerScreen(
    viewModel: PinnedPickerViewModel,
    onFinish: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.saved) {
        if (state.saved) onFinish()
    }

    val onTopicStep = state.step is PinnedPickerViewModel.Step.Topics

    BackHandler(enabled = onTopicStep) { viewModel.onBackToChats() }

    var query by remember(state.step::class) { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (val s = state.step) {
                                is PinnedPickerViewModel.Step.Chats ->
                                    stringResource(R.string.picker_add_shortcut_title)
                                is PinnedPickerViewModel.Step.Topics -> s.chat.title
                            },
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (state.step is PinnedPickerViewModel.Step.Topics) {
                            Text(
                                stringResource(R.string.picker_choose_topic),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (onTopicStep) viewModel.onBackToChats() else onFinish()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
        },
        bottomBar = {
            if (onTopicStep && state.hasChanges) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp)
                ) {
                    Button(
                        onClick = { viewModel.confirmSelectedTopics() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        val parts = buildList {
                            if (state.addedCount > 0) add("+${state.addedCount}")
                            if (state.removedCount > 0) add("-${state.removedCount}")
                        }
                        Text("${stringResource(R.string.picker_save_changes_prefix)} (${parts.joinToString(" ")})")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!state.loading && state.error == null) {
                SearchField(
                    query = query,
                    onChange = { query = it },
                    placeholder = when (state.step) {
                        is PinnedPickerViewModel.Step.Chats ->
                            stringResource(R.string.picker_search_chat_placeholder)
                        is PinnedPickerViewModel.Step.Topics ->
                            stringResource(R.string.picker_search_topic_placeholder)
                    }
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.loading -> CenteredSpinner()
                    state.error != null -> ErrorBlock(state.error!!)
                    state.step is PinnedPickerViewModel.Step.Chats -> ChatList(
                        chats = state.chats.filter {
                            query.isBlank() || it.title.contains(query, ignoreCase = true)
                        },
                        totalCount = state.chats.size,
                        query = query,
                        onClick = viewModel::onChatSelected
                    )
                    state.step is PinnedPickerViewModel.Step.Topics -> MultiTopicList(
                        topics = state.topics.filter {
                            query.isBlank() || it.name.contains(query, ignoreCase = true)
                        },
                        totalCount = state.topics.size,
                        query = query,
                        selected = state.selectedTopicIds,
                        lockedPrimary = state.primaryTopicIds,
                        onToggle = viewModel::toggleTopic
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onChange: (String) -> Unit,
    placeholder: String
) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            if (query.isNotBlank()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                }
            }
        },
        placeholder = { Text(placeholder) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun CenteredSpinner() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.picker_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorBlock(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ChatList(
    chats: List<ChatSummary>,
    totalCount: Int,
    query: String,
    onClick: (ChatSummary) -> Unit
) {
    if (chats.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.Chat,
            message = if (query.isNotBlank() && totalCount > 0)
                stringResource(R.string.picker_search_no_match, query)
            else stringResource(R.string.picker_no_chats_short)
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(chats, key = { it.chatId }) { chat ->
            ChatCard(chat = chat, onClick = { onClick(chat) })
        }
    }
}

@Composable
private fun ChatCard(chat: ChatSummary, onClick: () -> Unit) {
    val privateLabel = stringResource(R.string.picker_chat_private)
    val supergroupLabel = stringResource(R.string.picker_chat_supergroup)
    val forumSuffix = stringResource(R.string.picker_chat_forum_suffix)
    val kindLabel = when (chat.kind) {
        ChatKind.Private -> privateLabel
        ChatKind.Supergroup -> supergroupLabel
    }
    val kindIcon: ImageVector = when {
        chat.isForum -> Icons.Filled.Forum
        chat.kind == ChatKind.Private -> Icons.Filled.Person
        else -> Icons.Filled.Groups
    }
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        kindIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    chat.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (chat.isForum) "$kindLabel · $forumSuffix" else kindLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MultiTopicList(
    topics: List<TopicSummary>,
    totalCount: Int,
    query: String,
    selected: Set<Int>,
    lockedPrimary: Set<Int>,
    onToggle: (TopicSummary) -> Unit
) {
    if (topics.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.Tag,
            message = if (query.isNotBlank() && totalCount > 0)
                stringResource(R.string.picker_search_no_match, query)
            else stringResource(R.string.picker_no_topics)
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(topics, key = { it.forumTopicId }) { topic ->
            val isChecked = topic.forumTopicId in selected
            val isPrimary = topic.forumTopicId in lockedPrimary
            MultiTopicCard(
                topic = topic,
                isChecked = isChecked,
                isPrimary = isPrimary,
                onToggle = { onToggle(topic) }
            )
        }
    }
}

@Composable
private fun MultiTopicCard(
    topic: TopicSummary,
    isChecked: Boolean,
    isPrimary: Boolean,
    onToggle: () -> Unit
) {
    val generalLabel = stringResource(R.string.picker_topic_general)
    val closedLabel = stringResource(R.string.picker_topic_closed)
    val primaryLock = stringResource(R.string.picker_topic_primary_lock)
    val interactive = !isPrimary && !topic.isClosed
    val bg = when {
        isPrimary -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
        isChecked -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.surface
    }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (interactive) it.clickable { onToggle() } else it }
            .let { if (topic.isClosed && !isPrimary) it.alpha(0.55f) else it },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = bg),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isPrimary) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            } else {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = { onToggle() },
                    enabled = interactive
                )
            }
            Spacer(Modifier.width(10.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (topic.isClosed) Icons.Filled.Lock else Icons.Filled.Tag,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    topic.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val tags = listOfNotNull(
                    if (topic.isGeneral) generalLabel else null,
                    if (topic.isClosed) closedLabel else null,
                    if (isPrimary) primaryLock else null
                )
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(72.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
