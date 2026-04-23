package com.autogram.app.domain.usecase

import android.content.Context
import com.autogram.app.domain.model.AppSettings
import com.autogram.app.domain.model.Conversation
import com.autogram.app.domain.model.QuickReply
import com.autogram.app.domain.repository.ConversationRepository
import com.autogram.app.domain.repository.QuickReplyRepository
import com.autogram.app.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Round-trip tests for the encrypted backup format. Crypto itself is tested
 * implicitly by the "import produces the same payload that export started
 * with" property. Specific edge cases (wrong password, corrupt magic, new
 * version) verify the sealed ImportResult surfaces.
 *
 * The use case touches filesDir only when applying photos — we pick test
 * inputs with no photoLocalPath so no FS writes happen. Context is mocked
 * accordingly.
 */
class ExportImportUseCaseTest {

    private lateinit var context: Context
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var quickReplyRepo: QuickReplyRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var useCase: ExportImportUseCase

    private val sampleSettings = AppSettings(retentionDays = 42, languageTag = "es")
    private val sampleQuickReplies = listOf(
        QuickReply(id = "qr1", label = "OK", textContent = "Todo bien", position = 0),
        QuickReply(id = "qr2", label = "Driving", textContent = "Conduciendo", position = 1)
    )
    private val sampleConversations = listOf(
        Conversation(
            id = "c1", targetChatId = "1001", targetTitle = "Merlin",
            botToken = "tok", isActive = true, createdAt = 1000L
        ),
        Conversation(
            id = "c2", targetChatId = "2002", targetTitle = "Ana",
            botToken = "tok", isActive = false, createdAt = 2000L,
            sortOrder = 10
        )
    )

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        // filesDir only touched when photos are present — return a tmp dir
        // regardless so any accidental FS call won't NPE the test.
        coEvery { context.filesDir } returns File(System.getProperty("java.io.tmpdir"))

        conversationRepo = mockk(relaxed = true)
        quickReplyRepo = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)

        coEvery { settingsRepo.get() } returns sampleSettings
        coEvery { quickReplyRepo.getAll() } returns sampleQuickReplies
        coEvery { conversationRepo.getAll() } returns sampleConversations

        // Pass-through TransactionRunner for unit tests — no Room,
        // just runs the block directly. Production binding wraps
        // Room's withTransaction (tested via instrumented tests).
        val passThroughRunner = object : TransactionRunner {
            override suspend fun <R> run(block: suspend () -> R): R = block()
        }
        useCase = ExportImportUseCase(
            context = context,
            conversationRepository = conversationRepo,
            quickReplyRepository = quickReplyRepo,
            settingsRepository = settingsRepo,
            transactionRunner = passThroughRunner
        )
    }

    @Test fun `round-trip restores original payload`() = runTest {
        val password = "s3cur3pass!".toCharArray()
        val bytes = useCase.export(password.copyOf())
        assertTrue("encrypted output should be non-trivial", bytes.size > 100)

        // Magic must be v2 ("AUGBKP02").
        val magic = bytes.copyOfRange(0, 8)
        assertEquals(0x32.toByte(), magic[7])  // '2'

        val result = useCase.import(bytes, password.copyOf())
        assertTrue("expected Success, got $result", result is ExportImportUseCase.ImportResult.Success)

        val restored = (result as ExportImportUseCase.ImportResult.Success).restored
        assertEquals(sampleSettings, restored.settings)
        assertEquals(sampleQuickReplies, restored.quickReplies)
        assertEquals(sampleConversations.size, restored.conversations.size)
    }

    @Test fun `wrong password fails with WrongPassword`() = runTest {
        val bytes = useCase.export("correct-pass".toCharArray())
        val result = useCase.import(bytes, "different-pass".toCharArray())
        assertEquals(ExportImportUseCase.ImportResult.WrongPassword, result)
    }

    @Test fun `tampered ciphertext fails with WrongPassword (GCM auth)`() = runTest {
        val bytes = useCase.export("pw".toCharArray()).copyOf()
        // Flip a byte in the ciphertext region (after magic + salt + iv = 36).
        bytes[50] = (bytes[50].toInt() xor 0xFF).toByte()
        val result = useCase.import(bytes, "pw".toCharArray())
        // GCM auth failure surfaces as WrongPassword (same sealed result).
        assertEquals(ExportImportUseCase.ImportResult.WrongPassword, result)
    }

    @Test fun `truncated file fails with Corrupt`() = runTest {
        val bytes = useCase.export("pw".toCharArray()).copyOfRange(0, 20)
        val result = useCase.import(bytes, "pw".toCharArray())
        assertEquals(ExportImportUseCase.ImportResult.Corrupt, result)
    }

    @Test fun `unknown magic fails with Corrupt`() = runTest {
        val bytes = useCase.export("pw".toCharArray()).copyOf()
        // Scramble the magic so it's not AUGBKP01 nor AUGBKP02 nor anything
        // recognizable. `looksLikeAugbkp` only fires when the first 4 bytes
        // match "AUGB" — if we change those, it's generic Corrupt.
        bytes[0] = 0x00; bytes[1] = 0x00; bytes[2] = 0x00; bytes[3] = 0x00
        val result = useCase.import(bytes, "pw".toCharArray())
        assertEquals(ExportImportUseCase.ImportResult.Corrupt, result)
    }

    @Test fun `AUGB magic with unknown version fails with UnsupportedVersion`() = runTest {
        val bytes = useCase.export("pw".toCharArray()).copyOf()
        // Keep AUGBKP but bump the version byte to something unknown (e.g. '9').
        bytes[7] = 0x39  // '9'
        val result = useCase.import(bytes, "pw".toCharArray())
        assertEquals(ExportImportUseCase.ImportResult.UnsupportedVersion, result)
    }

    @Test fun `two exports use distinct salts`() = runTest {
        val a = useCase.export("pw".toCharArray())
        val b = useCase.export("pw".toCharArray())
        val saltA = a.copyOfRange(8, 24)
        val saltB = b.copyOfRange(8, 24)
        assertNotEquals(
            "Salts must be random per export, not deterministic",
            saltA.toList(), saltB.toList()
        )
    }

    @Test fun `import applies payload via repositories`() = runTest {
        val bytes = useCase.export("pw".toCharArray())
        val settingsSlot = slot<AppSettings>()
        coEvery { settingsRepo.update(capture(settingsSlot)) } returns Unit

        val result = useCase.import(bytes, "pw".toCharArray())
        assertTrue(result is ExportImportUseCase.ImportResult.Success)

        coVerify { settingsRepo.update(any()) }
        assertEquals(sampleSettings, settingsSlot.captured)
    }
}
