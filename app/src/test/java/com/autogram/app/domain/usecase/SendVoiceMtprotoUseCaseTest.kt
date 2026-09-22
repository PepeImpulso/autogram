package com.autogram.app.domain.usecase

import android.content.Context
import com.autogram.app.domain.model.AppSettings
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.model.Message
import com.autogram.app.domain.model.MessageDirection
import com.autogram.app.domain.model.MessageState
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.MessageRepository
import com.autogram.app.domain.repository.SettingsRepository
import com.autogram.app.infrastructure.audio.AudioPlayer
import com.autogram.app.infrastructure.audio.AudioRecorder
import com.autogram.app.infrastructure.audio.EmptyMessageFilter
import com.autogram.app.infrastructure.audio.HapticCues
import com.autogram.app.infrastructure.audio.RecordingSounds
import com.autogram.app.infrastructure.platform.ConnectivityObserver
import com.autogram.app.infrastructure.privacy.AcceptableUseConsentStore
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
 * Tests for the testable surface of [SendVoiceMtprotoUseCase] — the
 * sendQuickReply path (text-only, no audio recorder needed) and the
 * preview state-machine helpers (cancelPreview / reset). Audio recording
 * itself is left to integration tests since it touches MediaRecorder.
 *
 * The decision branches verified here are the same ones that have leaked
 * bugs into Sentry in earlier versions: offline / unauthenticated → queue
 * instead of erroring, conversation gone → clear error, persist on every
 * exit path so nothing falls off the table.
 */
class SendVoiceMtprotoUseCaseTest {

    private lateinit var context: Context
    private lateinit var tdLib: TdLibSender
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var emptyFilter: EmptyMessageFilter
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var messageRepo: MessageRepository
    private lateinit var playReplyUseCase: PlayReplyUseCase
    private lateinit var recordingSounds: RecordingSounds
    private lateinit var hapticCues: HapticCues
    private lateinit var connectivity: ConnectivityObserver
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var acceptableUseConsentStore: AcceptableUseConsentStore
    private lateinit var authStateFlow: MutableStateFlow<AuthState>
    private lateinit var useCase: SendVoiceMtprotoUseCase

    private val sampleTarget = Conversation(
        id = "c1",
        targetChatId = "12345",
        targetTitle = "Merlin",
        isActive = true
    )

    @Before fun setUp() {
        context = mockk(relaxed = true)
        tdLib = mockk(relaxed = true)
        audioRecorder = mockk(relaxed = true)
        emptyFilter = mockk(relaxed = true)
        conversationRepo = mockk(relaxed = true)
        messageRepo = mockk(relaxed = true)
        playReplyUseCase = mockk(relaxed = true)
        recordingSounds = mockk(relaxed = true)
        hapticCues = mockk(relaxed = true)
        connectivity = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)
        audioPlayer = mockk(relaxed = true)
        acceptableUseConsentStore = mockk(relaxed = true)
        authStateFlow = MutableStateFlow(AuthState.Ready)

        every { tdLib.authState } returns authStateFlow
        every { connectivity.isOnline() } returns true
        every { acceptableUseConsentStore.isAccepted() } returns true
        coEvery { conversationRepo.getActive() } returns sampleTarget
        coEvery { conversationRepo.getById("c1") } returns sampleTarget
        coEvery { settingsRepo.get() } returns AppSettings()

        useCase = SendVoiceMtprotoUseCase(
            context = context,
            tdLibClient = tdLib,
            audioRecorder = audioRecorder,
            emptyMessageFilter = emptyFilter,
            conversationRepository = conversationRepo,
            messageRepository = messageRepo,
            playReplyUseCase = playReplyUseCase,
            recordingSounds = recordingSounds,
            hapticCues = hapticCues,
            connectivityObserver = connectivity,
            settingsRepository = settingsRepo,
            audioPlayer = audioPlayer,
            acceptableUseConsentStore = acceptableUseConsentStore
        )
    }

    // --- sendQuickReply happy path ---------------------------------------

    @Test fun `synthetic reply with a real session is neither sent nor queued`() = runTest {
        val target = sampleTarget.copy(id = "store-alex", targetChatId = "10001")
        coEvery { conversationRepo.getById(target.id) } returns target

        assertTrue(useCase.sendQuickReply("review reply", target.id).isFailure)

        coVerify(exactly = 0) { tdLib.send<TdApi.Object>(any()) }
        coVerify(exactly = 0) { messageRepo.insert(any()) }
    }

    @Test fun `synthetic active target is never queued when unauthenticated`() = runTest {
        authStateFlow.value = AuthState.Initializing
        coEvery { conversationRepo.getActive() } returns sampleTarget.copy(id = "store-family")

        assertTrue(useCase.sendQuickReply("review reply").isFailure)

        coVerify(exactly = 0) { tdLib.send<TdApi.Object>(any()) }
        coVerify(exactly = 0) { messageRepo.insert(any()) }
    }

    @Test fun `synthetic voice shortcut cannot enter the Telegram outbox`() = runTest {
        val voice = java.io.File.createTempFile("review-voice-", ".ogg")
        try {
            voice.writeBytes(byteArrayOf(1, 2, 3))
            coEvery { conversationRepo.getById("store-project") } returns
                sampleTarget.copy(id = "store-project")
            every { connectivity.isOnline() } returns false

            assertTrue(useCase.sendVoiceShortcut(voice.path, 1000, "store-project").isFailure)

            coVerify(exactly = 0) { tdLib.send<TdApi.Object>(any()) }
            coVerify(exactly = 0) { messageRepo.insert(any()) }
            assertTrue(voice.exists())
        } finally {
            voice.delete()
        }
    }

    @Test fun `sendQuickReply requires accepted messaging terms`() = runTest {
        every { acceptableUseConsentStore.isAccepted() } returns false

        val result = useCase.sendQuickReply("hola", null)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { tdLib.send<TdApi.Object>(any()) }
        coVerify(exactly = 0) { messageRepo.insert(any()) }
    }

    @Test fun `sendQuickReply success persists outgoing as SENT and updates lastRepliedAt`() = runTest {
        val tdMessage = TdApi.Message().apply { id = 42L }
        coEvery { tdLib.send<TdApi.Object>(any()) } returns tdMessage

        val result = useCase.sendQuickReply("hola", null)
        assertTrue(result.isSuccess)
        assertEquals(42L, result.getOrNull())

        val captured = slot<Message>()
        coVerify { messageRepo.insert(capture(captured)) }
        assertEquals(MessageState.SENT, captured.captured.state)
        assertEquals(42L, captured.captured.telegramMessageId)
        assertEquals("hola", captured.captured.textContent)

        coVerify { conversationRepo.setLastRepliedAt("c1", any()) }
        assertTrue(useCase.status.value is SendVoiceMtprotoUseCase.SendStatus.Sent)
    }

    // --- offline / unauth queues, doesn't error --------------------------

    @Test fun `sendQuickReply offline queues, status = QueuedOffline, does not call TDLib`() = runTest {
        every { connectivity.isOnline() } returns false

        val result = useCase.sendQuickReply("hola", null)
        // Offline path returns Result.success(-1L) so caller doesn't see a
        // throw — the message is now responsibility of RetryWorker.
        assertTrue(result.isSuccess)

        // Inserted as QUEUED_OFFLINE, never sent.
        val captured = slot<Message>()
        coVerify { messageRepo.insert(capture(captured)) }
        assertEquals(MessageState.QUEUED_OFFLINE, captured.captured.state)

        coVerify(exactly = 0) { tdLib.send<TdApi.Object>(any()) }
        assertTrue(useCase.status.value is SendVoiceMtprotoUseCase.SendStatus.QueuedOffline)
    }

    @Test fun `sendQuickReply auth-not-ready also queues`() = runTest {
        authStateFlow.value = AuthState.Initializing

        val result = useCase.sendQuickReply("hola", null)
        assertTrue(result.isSuccess)

        val captured = slot<Message>()
        coVerify { messageRepo.insert(capture(captured)) }
        assertEquals(MessageState.QUEUED_OFFLINE, captured.captured.state)
        coVerify(exactly = 0) { tdLib.send<TdApi.Object>(any()) }
    }

    // --- no target → error -----------------------------------------------

    @Test fun `sendQuickReply with no active conversation surfaces error`() = runTest {
        coEvery { conversationRepo.getActive() } returns null

        val result = useCase.sendQuickReply("hola", null)
        assertTrue(result.isFailure)
        assertTrue(useCase.status.value is SendVoiceMtprotoUseCase.SendStatus.Error)
        coVerify(exactly = 0) { tdLib.send<TdApi.Object>(any()) }
    }

    // --- explicit targetId resolves via getById --------------------------

    @Test fun `sendQuickReply with explicit targetId resolves via getById`() = runTest {
        val secondary = sampleTarget.copy(id = "c2", targetChatId = "999")
        coEvery { conversationRepo.getById("c2") } returns secondary
        coEvery { tdLib.send<TdApi.Object>(any()) } returns TdApi.Message().apply { id = 7L }

        useCase.sendQuickReply("hi", targetConversationId = "c2")
        coVerify { conversationRepo.getById("c2") }
        coVerify(exactly = 0) { conversationRepo.getActive() }
    }

    // --- TDLib error path ------------------------------------------------

    @Test fun `sendQuickReply TDLib error persists FAILED + sets Error status`() = runTest {
        coEvery { tdLib.send<TdApi.Object>(any()) } returns TdApi.Error(403, "FORBIDDEN")

        val result = useCase.sendQuickReply("hola", null)
        // Result should still be failure since the message didn't go out.
        assertTrue(result.isFailure)

        val captured = slot<Message>()
        coVerify { messageRepo.insert(capture(captured)) }
        assertEquals(MessageState.FAILED, captured.captured.state)
        assertTrue(useCase.status.value is SendVoiceMtprotoUseCase.SendStatus.Error)
    }

    // --- TDLib throw is caught, persisted, status=Error ------------------

    @Test fun `sendQuickReply TDLib exception caught and surfaced`() = runTest {
        coEvery { tdLib.send<TdApi.Object>(any()) } throws RuntimeException("connection reset")

        val result = useCase.sendQuickReply("hola", null)
        assertTrue(result.isFailure)
        assertTrue(useCase.status.value is SendVoiceMtprotoUseCase.SendStatus.Error)

        // Even when sending crashes, the failed-attempt row is persisted
        // so the user can see it in History.
        val captured = slot<Message>()
        coVerify { messageRepo.insert(capture(captured)) }
        assertEquals(MessageState.FAILED, captured.captured.state)
    }

    // --- preview state machine -------------------------------------------

    @Test fun `cancelPreview clears state and stops audio player`() = runTest {
        useCase.cancelPreview()
        coVerify { audioPlayer.stop() }
        assertEquals(SendVoiceMtprotoUseCase.SendStatus.Idle, useCase.status.value)
    }

    @Test fun `reset returns status to Idle`() {
        useCase.reset()
        assertEquals(SendVoiceMtprotoUseCase.SendStatus.Idle, useCase.status.value)
    }

    // --- direction + sender always OUTGOING for sent messages ------------

    @Test fun `persisted message direction is OUTGOING for quick reply`() = runTest {
        coEvery { tdLib.send<TdApi.Object>(any()) } returns TdApi.Message().apply { id = 1L }

        useCase.sendQuickReply("hola", null)

        val captured = slot<Message>()
        coVerify { messageRepo.insert(capture(captured)) }
        assertEquals(MessageDirection.OUTGOING, captured.captured.direction)
    }
}
