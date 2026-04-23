package com.autogram.app.infrastructure.telegram.mtproto

import org.drinkless.tdlib.TdApi
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin query layer on top of [TdLibClient] for listing chats and forum topics.
 *
 * TDLib's chat list is populated asynchronously after login via UpdateNewChat
 * events. By the time the user opens the picker post-login, the main chat list
 * is normally already in cache; we still issue LoadChats first to trigger any
 * remaining backfill, then ask for chatIds and resolve each via GetChat.
 */
@Singleton
class TdLibChatDirectory @Inject constructor(private val client: TdLibClient) {

    suspend fun listTargetChats(limit: Int = 50): List<ChatSummary> {
        // Best-effort: tell TDLib to warm the main list. A 404 Error means
        // the list is already fully loaded — not a failure.
        runCatching {
            client.send(TdApi.LoadChats(TdApi.ChatListMain(), limit))
        }.onFailure { Timber.d(it, "LoadChats threw — continuing with cached chats") }

        val chatsObj = client.send(TdApi.GetChats(TdApi.ChatListMain(), limit))
        if (chatsObj is TdApi.Error) {
            Timber.w("GetChats failed: ${chatsObj.code} ${chatsObj.message}")
            return emptyList()
        }
        val chats = chatsObj as? TdApi.Chats ?: run {
            Timber.w("GetChats returned unexpected ${chatsObj.javaClass.simpleName}")
            return emptyList()
        }
        val out = mutableListOf<ChatSummary>()
        for (id in chats.chatIds) {
            resolveChatSummary(id)?.let { out += it }
        }
        return out
    }

    suspend fun listForumTopics(chatId: Long, limit: Int = 100): List<TopicSummary> {
        val res = client.send(TdApi.GetForumTopics(chatId, "", 0, 0, 0, limit))
        if (res is TdApi.Error) {
            Timber.w("GetForumTopics failed for chatId=$chatId: ${res.code} ${res.message}")
            return emptyList()
        }
        val topics = res as? TdApi.ForumTopics ?: run {
            Timber.w("GetForumTopics returned unexpected ${res.javaClass.simpleName}")
            return emptyList()
        }
        return topics.topics.map { t ->
            TopicSummary(
                forumTopicId = t.info.forumTopicId,
                name = if (t.info.isGeneral && t.info.name.isBlank()) "General" else t.info.name,
                isGeneral = t.info.isGeneral,
                isClosed = t.info.isClosed
            )
        }.sortedWith(compareByDescending<TopicSummary> { it.isGeneral }.thenBy { it.name })
    }

    private suspend fun resolveChatSummary(chatId: Long): ChatSummary? {
        val chatObj = client.send(TdApi.GetChat(chatId))
        if (chatObj !is TdApi.Chat) return null
        val kind = classify(chatObj.type) ?: return null
        val isForum = when (val t = chatObj.type) {
            is TdApi.ChatTypeSupergroup -> {
                val sg = client.send(TdApi.GetSupergroup(t.supergroupId))
                (sg as? TdApi.Supergroup)?.isForum == true
            }
            else -> false
        }
        return ChatSummary(
            chatId = chatObj.id,
            title = chatObj.title.ifBlank { "(sin título)" },
            kind = kind,
            isForum = isForum
        )
    }

    // We only surface chats that make sense as voice-note targets: private DMs
    // (including bots) and supergroups (forum or not). Channels, basic groups,
    // and secret chats are intentionally skipped.
    private fun classify(type: TdApi.ChatType): ChatKind? = when (type) {
        is TdApi.ChatTypePrivate -> ChatKind.Private
        is TdApi.ChatTypeSupergroup -> if (type.isChannel) null else ChatKind.Supergroup
        else -> null
    }
}

data class ChatSummary(
    val chatId: Long,
    val title: String,
    val kind: ChatKind,
    val isForum: Boolean
)

data class TopicSummary(
    val forumTopicId: Int,
    val name: String,
    val isGeneral: Boolean,
    val isClosed: Boolean
)

enum class ChatKind { Private, Supergroup }
