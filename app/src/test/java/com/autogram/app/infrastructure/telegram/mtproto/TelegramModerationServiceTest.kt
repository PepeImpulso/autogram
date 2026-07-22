package com.autogram.app.infrastructure.telegram.mtproto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TelegramModerationServiceTest {
    private lateinit var sender: FakeTdLibSender
    private lateinit var service: TelegramModerationService

    @Before
    fun setUp() {
        sender = FakeTdLibSender()
        service = TelegramModerationService(sender)
    }

    @Test
    fun `block private chat blocks its user`() = runTest {
        val chat = TdApi.Chat().apply {
            id = 100
            type = TdApi.ChatTypePrivate(42)
        }
        sender.responses += chat
        sender.responses += TdApi.Ok()

        assertSame(TelegramModerationAction.Completed, service.blockChat(100))

        val request = sender.queries.filterIsInstance<TdApi.SetMessageSenderBlockList>().single()
        assertEquals(42L, (request.senderId as TdApi.MessageSenderUser).userId)
        assertTrue(request.blockList is TdApi.BlockListMain)
    }

    @Test
    fun `block supergroup chat blocks the chat sender`() = runTest {
        val chat = TdApi.Chat().apply {
            id = 200
            type = TdApi.ChatTypeSupergroup(7, false)
        }
        sender.responses += chat
        sender.responses += TdApi.Ok()

        assertSame(TelegramModerationAction.Completed, service.blockChat(200))

        val request = sender.queries.filterIsInstance<TdApi.SetMessageSenderBlockList>().single()
        assertEquals(200L, (request.senderId as TdApi.MessageSenderChat).chatId)
    }

    @Test
    fun `report maps Telegram option prompt`() = runTest {
        val optionId = byteArrayOf(1, 2, 3)
        sender.responses += TdApi.ReportChatResultOptionRequired(
                "Why are you reporting this?",
                arrayOf(TdApi.ReportOption(optionId, "Spam"))
            )

        val step = service.report(300, longArrayOf(99)) as TelegramReportStep.ChooseOption

        assertEquals("Why are you reporting this?", step.title)
        assertEquals("Spam", step.options.single().label)
        assertArrayEquals(optionId, step.options.single().id)
    }

    @Test
    fun `report sends selected option and message identifiers`() = runTest {
        val optionId = byteArrayOf(9)
        sender.responses += TdApi.ReportChatResultOk()

        assertSame(
            TelegramReportStep.Submitted,
            service.report(300, longArrayOf(99), optionId, "details")
        )

        val request = sender.queries.filterIsInstance<TdApi.ReportChat>().single()
        assertEquals(300L, request.chatId)
        assertArrayEquals(longArrayOf(99), request.messageIds)
        assertArrayEquals(optionId, request.optionId)
        assertEquals("details", request.text)
    }

    private class FakeTdLibSender : TdLibSender {
        override val authState: StateFlow<AuthState> = MutableStateFlow(AuthState.Ready)
        val responses = mutableListOf<TdApi.Object>()
        val queries = mutableListOf<TdApi.Function<*>>()

        override suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): TdApi.Object {
            queries += query
            return responses.removeAt(0)
        }
    }
}
