package com.autogram.app.ui.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
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
class PinnedPickerActivity : ComponentActivity() {

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

    // System back should stay inside the activity when we're on the Topics step:
    // go back to the Chats list instead of closing the picker outright.
    BackHandler(enabled = onTopicStep) { viewModel.onBackToChats() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (val s = state.step) {
                            is PinnedPickerViewModel.Step.Chats -> "Añadir acceso rápido"
                            is PinnedPickerViewModel.Step.Topics -> s.chat.title
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (onTopicStep) viewModel.onBackToChats() else onFinish()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                }
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
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val parts = buildList {
                            if (state.addedCount > 0) add("+${state.addedCount}")
                            if (state.removedCount > 0) add("-${state.removedCount}")
                        }
                        Text("Guardar cambios (${parts.joinToString(" ")})")
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.loading -> CenteredSpinner()
                state.error != null -> ErrorBlock(state.error!!)
                state.step is PinnedPickerViewModel.Step.Chats -> ChatList(
                    chats = state.chats,
                    onClick = viewModel::onChatSelected
                )
                state.step is PinnedPickerViewModel.Step.Topics -> MultiTopicList(
                    topics = state.topics,
                    selected = state.selectedTopicIds,
                    lockedPrimary = state.primaryTopicIds,
                    onToggle = viewModel::toggleTopic
                )
            }
        }
    }
}

@Composable
private fun CenteredSpinner() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorBlock(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ChatList(chats: List<ChatSummary>, onClick: (ChatSummary) -> Unit) {
    if (chats.isEmpty()) {
        EmptyState("No se encontraron chats disponibles.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(chats, key = { it.chatId }) { chat ->
            ListItem(
                headlineContent = { Text(chat.title) },
                supportingContent = {
                    Text(
                        buildString {
                            append(
                                when (chat.kind) {
                                    ChatKind.Private -> "Chat privado"
                                    ChatKind.Supergroup -> "Supergrupo"
                                }
                            )
                            if (chat.isForum) append(" · con topics")
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick(chat) }
            )
        }
    }
}

@Composable
private fun MultiTopicList(
    topics: List<TopicSummary>,
    selected: Set<Int>,
    lockedPrimary: Set<Int>,
    onToggle: (TopicSummary) -> Unit
) {
    if (topics.isEmpty()) {
        EmptyState("Este chat no tiene topics visibles.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(topics, key = { it.forumTopicId }) { topic ->
            val isChecked = topic.forumTopicId in selected
            val isPrimary = topic.forumTopicId in lockedPrimary
            val tags = listOfNotNull(
                if (topic.isGeneral) "General" else null,
                if (topic.isClosed) "cerrado" else null,
                if (isPrimary) "principal (Merlin)" else null
            ).joinToString(" · ")
            ListItem(
                leadingContent = {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = { onToggle(topic) },
                        enabled = !isPrimary
                    )
                },
                headlineContent = { Text(topic.name) },
                supportingContent = {
                    if (tags.isNotEmpty()) Text(tags, style = MaterialTheme.typography.bodySmall)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .let { if (isPrimary) it else it.clickable { onToggle(topic) } }
                    .let { if (topic.isClosed || isPrimary) it.alpha(0.6f) else it }
            )
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
    }
}
