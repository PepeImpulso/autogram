package com.autogram.app.domain.usecase

import com.autogram.app.domain.model.AppSettings
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.MessageState
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.infrastructure.telegram.mtproto.AuthState
import com.autogram.app.infrastructure.telegram.mtproto.TdLibSender
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the outbox retry pipeline. The original logic lived inside
 * RetryWorker (a CoroutineWorker), which is unreachable from a JVM unit test
 * without Robolectric. We extracted the logic into [OutboxRetryUseCase] —
 * pure Kotlin, depends only on a [TdLibSender] interface — so these tests
 * cover every branch end-to-end.
 *
 * What's exercised:
 *  - Auth not Ready → retry.
 *  - Empty outbox → success.
 *  - Conversation deleted while queued → PERMANENTLY_FAILED with reason.
 *  - Missing audio file → PERMANENTLY_FAILED with "no content".
 *  - Telegram error response → failAttempt (FAILED if under retry budget,
 *    PERMANENTLY_FAILED at the limit).
 *  - Successful send → SENT + telegramMessageId + conversation.lastRepliedAt.
 *  - Crash during send → caught and counts as a retry attempt.
 */
class OutboxRetryUseCaseTest {

    private lateinit var tdLib: TdLibSender
    private lateinit var messageRepo: MessageRepository
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var authStateFlow: MutableStateFlow<AuthState>
    private lateinit var useCase: OutboxRetryUseCase

    private val defaultSettings = AppSettings(maxRetryAttempts = 3)
    private val sampleConversation = Conversation(
        id = "c1",
        targetChatId = "12345",
        targetTitle = "Merlin"
    )

    @Before fun setUp() {
        tdLib = mockk()
        messageRepo = mockk(relaxed = true)
        conversationRepo = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)
        authStateFlow = MutableStateFlow(AuthState.Ready)
        every { tdLib.authState } returns authStateFlow
        coEvery { settingsRepo.get() } returns defaultSettings
        coEvery { messageRepo.getOutbox(any(), any()) } returns emptyList()
        coEvery { conversationRepo.getById("c1") } returns sampleConversation

        useCase = OutboxRetryUseCase(tdLib, messageRepo, conversationRepo, settingsRepo)
    }

    private fun textMessage(id: String = "m1", retries: Int = 0) = Message(
        id = id,
        conversationId = "c1",
        direction = MessageDirection.OUTGOING,
        state = MessageState.QUEUED_OFFLINE,
        textContent = "hello",
        retryCount = retries
    )

    @Test fun `auth not ready returns RETRY without touching outbox`() = runTest {
        authStateFlow.value = AuthState.Initializing
        val outcome = useCase.runOnce()
        assertEquals(OutboxRetryUseCase.Outcome.RETRY, outcome)
        coVerify(exactly = 0) { messageRepo.getOutbox(any(), any()) }
    }

    @Test fun `empty outbox returns SUCCESS without sending`() = runTest {
        coEvery { messageRepo.getOutbox(any(), any()) } returns emptyList()
        val outcome = useCase.runOnce()
        assertEquals(OutboxRetryUseCase.Outcome.SUCCESS, outcome)
        coVerify(exactly = 0) { tdLib.send<TdApi.Object>(any()) }
    }

    @Test fun `legacy synthetic outbox is quarantined after real account login`() = runTest {
        val messages = listOf("store-alex", "store-family", "store-project").map {
            textMessage(id = "queued-$it").copy(conversationId = it)
        }
        coEvery { messageRepo.getOutbox(any(), any()) } returns messages

        assertEquals(OutboxRetryUseCase.Outcome.SUCCESS, useCase.runOnce())

        messages.forEach { original ->
            coVerify(exactly = 1) {
                messageRepo.update(match {
                    it.id == original.id && it.state == MessageState.PERMANENTLY_FAILED &&
                        it.errorMessage == "Synthetic review messages cannot be sent to Telegram"
                })
            }
        }
        coVerify(exactly = 0) { tdLib.send<TdApi.Object>(any()) }
        coVerify(exactly = 0) { conversationRepo.getById(any()) }
    }

    @Test fun `quarantining a synthetic voice message does not block a real reply`() = runTest {
        val synthetic = textMessage("synthetic-voice").copy(
            conversationId = "store-alex",
            textContent = null,
            audioFilePath = "/not-a-real-review-file.ogg"
        )
        val real = textMessage("real-reply")
        coEvery { messageRepo.getOutbox(any(), any()) } returns listOf(synthetic, real)
        coEvery { tdLib.send<TdApi.Object>(any()) } returns TdApi.Message().apply { id = 123L }

        assertEquals(OutboxRetryUseCase.Outcome.SUCCESS, useCase.runOnce())

        coVerify(exactly = 1) { tdLib.send<TdApi.Object>(any()) }
        coVerify { messageRepo.update(match {
            it.id == "synthetic-voice" && it.state == MessageState.PERMANENTLY_FAILED
        }) }
        coVerify { messageRepo.update(match {
            it.id == "real-reply" && it.state == MessageState.SENT && it.telegramMessageId == 123L
        }) }
    }

    @Test fun `successful send marks SENT, sets telegramMessageId and updates lastRepliedAt`() = runTest {
        val msg = textMessage()
        coEvery { messageRepo.getOutbox(any(), any()) } returns listOf(msg)
        // TdApi.Message is a Java class with public fields — use a real
        // instance so the .id field is readable without mocking.
        val tdResult = TdApi.Message().apply { id = 999L }
        coEvery { tdLib.send<TdApi.Object>(any()) } returns tdResult

        useCase.runOnce()

        val updates = mutableListOf<Message>()
        coVerify { messageRepo.update(capture(updates)) }
        // Two updates expected: SENDING then SENT.
        assertTrue(updates.any { it.state == MessageState.SENDING })
        val sent = updates.first { it.state == MessageState.SENT }
        assertEquals(999L, sent.telegramMessageId)
        coVerify { conversationRepo.setLastRepliedAt("c1", any()) }
    }

    @Test fun `conversation gone marks PERMANENTLY_FAILED with reason`() = runTest {
        val msg = textMessage()
        coEvery { messageRepo.getOutbox(any(), any()) } returns listOf(msg)
        coEvery { conversationRepo.getById("c1") } returns null

        useCase.runOnce()

        val captured = slot<Message>()
        coVerify { messageRepo.update(capture(captured)) }
        assertEquals(MessageState.PERMANENTLY_FAILED, captured.captured.state)
        assertEquals("Target conversation no longer exists", captured.captured.errorMessage)
        // Don't even attempt to send.
        coVerify(exactly = 0) { tdLib.send<TdApi.Object>(any()) }
    }

    @Test fun `audio message with missing file marks PERMANENTLY_FAILED`() = runTest {
        val msg = Message(
            id = "voice1",
            conversationId = "c1",
            direction = MessageDirection.OUTGOING,
            state = MessageState.QUEUED_OFFLINE,
            audioFilePath = "/tmp/nope-i-dont-exist.ogg",
            audioDurationMs = 1000L
        )
        coEvery { messageRepo.getOutbox(any(), any()) } returns listOf(msg)

        useCase.runOnce()

        val updates = mutableListOf<Message>()
        coVerify { messageRepo.update(capture(updates)) }
        val finalState = updates.last { it.state == MessageState.PERMANENTLY_FAILED }
        assertEquals("No content to send", finalState.errorMessage)
        coVerify(exactly = 0) { tdLib.send<TdApi.Object>(any()) }
    }

    @Test fun `Telegram error below retry budget marks FAILED and increments retryCount`() = runTest {
        val msg = textMessage(retries = 0)  // budget=3, so 1st failure → FAILED (still retriable)
        coEvery { messageRepo.getOutbox(any(), any()) } returns listOf(msg)
        coEvery { tdLib.send<TdApi.Object>(any()) } returns TdApi.Error(429, "rate-limited")

        useCase.runOnce()

        val updates = mutableListOf<Message>()
        coVerify { messageRepo.update(capture(updates)) }
        val final = updates.last { it.state == MessageState.FAILED }
        assertEquals(1, final.retryCount)
        assertTrue(final.errorMessage!!.contains("429"))
        assertTrue(final.errorMessage!!.contains("rate-limited"))
    }

    @Test fun `Telegram error at retry budget marks PERMANENTLY_FAILED`() = runTest {
        // retries=2, max=3 → next attempt (3rd) should be terminal.
        val msg = textMessage(retries = 2)
        coEvery { messageRepo.getOutbox(any(), any()) } returns listOf(msg)
        coEvery { tdLib.send<TdApi.Object>(any()) } returns TdApi.Error(500, "server")

        useCase.runOnce()

        val updates = mutableListOf<Message>()
        coVerify { messageRepo.update(capture(updates)) }
        val final = updates.last()
        assertEquals(MessageState.PERMANENTLY_FAILED, final.state)
        assertEquals(3, final.retryCount)
    }

    @Test fun `exception during send counts as failed attempt, not RETRY`() = runTest {
        val msg = textMessage(retries = 0)
        coEvery { messageRepo.getOutbox(any(), any()) } returns listOf(msg)
        coEvery { tdLib.send<TdApi.Object>(any()) } throws RuntimeException("network blew up")

        val outcome = useCase.runOnce()
        assertEquals(OutboxRetryUseCase.Outcome.SUCCESS, outcome)  // outer loop OK

        val updates = mutableListOf<Message>()
        coVerify { messageRepo.update(capture(updates)) }
        val final = updates.last { it.state == MessageState.FAILED }
        assertEquals(1, final.retryCount)
        assertTrue(final.errorMessage!!.contains("network blew up"))
    }

    @Test fun `outer crash returns RETRY so WorkManager re-runs`() = runTest {
        // Simulate a deeper failure (settingsRepo throws).
        coEvery { settingsRepo.get() } throws RuntimeException("db locked")
        val outcome = useCase.runOnce()
        assertEquals(OutboxRetryUseCase.Outcome.RETRY, outcome)
    }

    @Test fun `unexpected TDLib response type is a fail attempt`() = runTest {
        val msg = textMessage(retries = 0)
        coEvery { messageRepo.getOutbox(any(), any()) } returns listOf(msg)
        // Some non-Message, non-Error type from TDLib (we use Ok which is a
        // valid TdApi.Object subclass not handled in the when() branches).
        coEvery { tdLib.send<TdApi.Object>(any()) } returns TdApi.Ok()

        useCase.runOnce()

        val updates = mutableListOf<Message>()
        coVerify { messageRepo.update(capture(updates)) }
        val final = updates.last { it.state == MessageState.FAILED }
        assertTrue(final.errorMessage!!.contains("Unexpected TDLib response"))
    }
}
