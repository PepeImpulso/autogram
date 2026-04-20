package com.autogram.app.ui.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
 * Two-step flow for picking where voice messages should be sent:
 *   Step 1 — choose a chat (private DMs + supergroups).
 *   Step 2 — if the chat is a forum supergroup, choose a topic; otherwise the
 *            selection persists immediately at Step 1.
 */
@AndroidEntryPoint
class TargetPickerActivity : ComponentActivity() {

    private val viewModel: TargetPickerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutogramTheme {
                TargetPickerScreen(
                    viewModel = viewModel,
                    onFinish = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetPickerScreen(
    viewModel: TargetPickerViewModel,
    onFinish: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.saved) {
        if (state.saved) onFinish()
    }

    val onTopicStep = state.step is TargetPickerViewModel.Step.Topics

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (val s = state.step) {
                            is TargetPickerViewModel.Step.Chats -> "Elige chat"
                            is TargetPickerViewModel.Step.Topics -> s.chat.title
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
                state.step is TargetPickerViewModel.Step.Chats -> ChatList(
                    chats = state.chats,
                    onClick = viewModel::onChatSelected
                )
                state.step is TargetPickerViewModel.Step.Topics -> TopicList(
                    topics = state.topics,
                    onClick = viewModel::onTopicSelected
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
        EmptyState("No se encontraron chats disponibles.\nAsegúrate de haber iniciado al menos una conversación en Telegram.")
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
                    .clickableListItem { onClick(chat) }
            )
        }
    }
}

@Composable
private fun TopicList(topics: List<TopicSummary>, onClick: (TopicSummary) -> Unit) {
    if (topics.isEmpty()) {
        EmptyState("Este chat no tiene topics visibles.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(topics, key = { it.forumTopicId }) { topic ->
            val tags = listOfNotNull(
                if (topic.isGeneral) "General" else null,
                if (topic.isClosed) "cerrado" else null
            ).joinToString(" · ")
            ListItem(
                headlineContent = { Text(topic.name) },
                supportingContent = {
                    if (tags.isNotEmpty()) Text(tags, style = MaterialTheme.typography.bodySmall)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableListItem { onClick(topic) }
                    .let { if (topic.isClosed) it.alpha(0.6f) else it }
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

// ListItem doesn't expose its own onClick in M3 — wrap with a clickable modifier.
private fun Modifier.clickableListItem(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
