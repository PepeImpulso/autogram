package com.autogram.app.domain.usecase

import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.MessageState
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.infrastructure.telegram.mtproto.TdLibSender
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MarkNotificationMessageReadUseCaseTest {
    private val messages = mockk<MessageRepository>()
    private val conversations = mockk<ConversationRepository>()
    private val sender = mockk<TdLibSender>()
    private val useCase = MarkNotificationMessageReadUseCase(messages, conversations, sender)
    private val message = Message(
        id = "message", conversationId = "real-chat", direction = MessageDirection.INCOMING,
        state = MessageState.RECEIVED, telegramMessageId = 123L
    )
    private val conversation = Conversation(id = "real-chat", targetChatId = "456", targetTitle = "Test")

    @Before fun setUp() {
        coEvery { messages.getById("message") } returns message
        coEvery { conversations.getById("real-chat") } returns conversation
        coEvery { sender.send<TdApi.Object>(any()) } returns TdApi.Ok()
    }

    @Test fun `real notification marks only its own Telegram message read`() = runTest {
        useCase("message", "real-chat")
        coVerify(exactly = 1) { sender.send<TdApi.Object>(match {
            it is TdApi.ViewMessages && it.chatId == 456L &&
                it.messageIds.contentEquals(longArrayOf(123L)) && it.forceRead
        }) }
    }

    @Test fun `synthetic notifications never reach Telegram even after a real login`() = runTest {
        listOf("store-alex", "store-family", "store-project").forEach {
            useCase("store-message-1", it)
        }
        coVerify(exactly = 0) { messages.getById(any()) }
        coVerify(exactly = 0) { conversations.getById(any()) }
        coVerify(exactly = 0) { sender.send<TdApi.Object>(any()) }
    }

    @Test fun `mismatched message and conversation never reach Telegram`() = runTest {
        coEvery { messages.getById("message") } returns message.copy(conversationId = "store-alex")
        useCase("message", "real-chat")
        coVerify(exactly = 0) { sender.send<TdApi.Object>(any()) }
    }

    @Test fun `missing notification identities never reach Telegram`() = runTest {
        useCase(null, "real-chat")
        useCase("message", null)
        coEvery { messages.getById("message") } returns null
        useCase("message", "real-chat")
        coVerify(exactly = 0) { sender.send<TdApi.Object>(any()) }
    }

    @Test fun `missing or invalid Telegram identifiers never reach Telegram`() = runTest {
        coEvery { messages.getById("message") } returns message.copy(telegramMessageId = null)
        useCase("message", "real-chat")
        coEvery { messages.getById("message") } returns message
        coEvery { conversations.getById("real-chat") } returns conversation.copy(targetChatId = "invalid")
        useCase("message", "real-chat")
        coVerify(exactly = 0) { sender.send<TdApi.Object>(any()) }
    }

    @Test fun `Telegram rejection is surfaced rather than treated as acknowledged`() = runTest {
        coEvery { sender.send<TdApi.Object>(any()) } returns TdApi.Error(400, "MESSAGE_ID_INVALID")
        assertTrue(runCatching { useCase("message", "real-chat") }.isFailure)
    }
}
