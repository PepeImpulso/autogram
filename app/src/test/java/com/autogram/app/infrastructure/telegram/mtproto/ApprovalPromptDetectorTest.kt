package com.autogram.app.infrastructure.telegram.mtproto

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the narrow detection of Hermes exec-approval requests. The fixtures
 * mirror what the Hermes gateway builds (Telegram adapter button card and the
 * `_format_exec_approval_fallback` text).
 */
class ApprovalPromptDetectorTest {

    private val longCommand = "cd /srv/app && for f in $(ls *.log); do tar czf \"/tmp/\$f.tgz\" \"\$f\" && " +
        "curl -s -X POST https://example.invalid/upload -H 'X-Token: abc123' --data-binary @\"/tmp/\$f.tgz\"; done"

    private fun cardText(command: String = longCommand) =
        "⚠️ Hermes wants to run a command that needs your OK\n\n" +
            "$command\n\n" +
            "Why it was flagged: pipe to remote host\n" +
            "If you don't answer within 5 minutes it will NOT run."

    private fun callback(label: String, data: String) = TdApi.InlineKeyboardButton().apply {
        text = label
        type = TdApi.InlineKeyboardButtonTypeCallback(data.toByteArray(Charsets.UTF_8))
    }

    private fun keyboard(vararg rows: List<TdApi.InlineKeyboardButton>) =
        TdApi.ReplyMarkupInlineKeyboard(rows.map { it.toTypedArray() }.toTypedArray())

    private fun hermesCardKeyboard(id: Int = 7) = keyboard(
        listOf(callback("✅ Allow Once", "ea:once:$id"), callback("✅ Session", "ea:session:$id")),
        listOf(callback("✅ Always", "ea:always:$id"), callback("❌ Deny", "ea:deny:$id"))
    )

    private fun fallbackText(command: String = longCommand) =
        "⚠️ Hermes wants to run a command that needs your OK\n" +
            "$command\n" +
            "Why it was flagged: pipe to remote host\n\n" +
            "Reply /approve to run it once, /approve session to allow this pattern for the rest of " +
            "this session, /approve always to allow it permanently, or /deny to cancel.\n" +
            "If you don't answer within 5 minutes it will NOT run."

    // --- positive: button card -------------------------------------------

    @Test fun `button card with Hermes approval callbacks is an approval prompt`() {
        assertTrue(ApprovalPromptDetector.isApprovalPrompt(cardText(), hermesCardKeyboard()))
    }

    @Test fun `card is detected from the keyboard even if the wording changes`() {
        assertTrue(ApprovalPromptDetector.isApprovalPrompt("Totally different wording", hermesCardKeyboard()))
    }

    @Test fun `card with only once and deny is detected (smart deny has no session tier)`() {
        val kb = keyboard(listOf(callback("Allow Once", "ea:once:12"), callback("Deny", "ea:deny:12")))
        assertTrue(ApprovalPromptDetector.isApprovalPrompt(cardText(), kb))
    }

    // --- positive: text fallback -----------------------------------------

    @Test fun `text fallback with header, typed commands and deadline is detected`() {
        assertTrue(ApprovalPromptDetector.isApprovalPrompt(fallbackText(), null))
    }

    @Test fun `smart deny fallback header is detected`() {
        val text = fallbackText().replace(
            "Hermes wants to run a command that needs your OK",
            "Smart DENY — owner override for one operation:"
        )
        assertTrue(ApprovalPromptDetector.isApprovalPrompt(text, null))
    }

    @Test fun `fallback is detected when bold markers survived as literal asterisks`() {
        val text = fallbackText().replace(
            "⚠️ Hermes wants to run a command that needs your OK",
            "⚠️ **Hermes wants to run a command that needs your OK**"
        )
        assertTrue(ApprovalPromptDetector.isApprovalPrompt(text, null))
    }

    // --- negative --------------------------------------------------------

    @Test fun `normal reply with a code block is not an approval prompt`() {
        val text = "Here is the script you asked for:\n```bash\n$longCommand\n```\nRun it when ready."
        assertFalse(ApprovalPromptDetector.isApprovalPrompt(text, null))
    }

    @Test fun `normal reply mentioning authorization is not an approval prompt`() {
        assertFalse(ApprovalPromptDetector.isApprovalPrompt("Necesito que me autorices para revisar el correo, ahora te lo explico.", null))
        assertFalse(ApprovalPromptDetector.isApprovalPrompt("You can /approve it later and /deny the rest; it will NOT run twice.", null))
    }

    @Test fun `text with only the Hermes header is not enough`() {
        val text = "⚠️ Hermes wants to run a command that needs your OK\n$longCommand"
        assertFalse(ApprovalPromptDetector.isApprovalPrompt(text, null))
    }

    @Test fun `header quoted in the middle of a message is not a fallback prompt`() {
        val text = "Earlier I saw: ${fallbackText()}"
        assertFalse(ApprovalPromptDetector.isApprovalPrompt(text, null))
    }

    @Test fun `approval-like text without keyboard or fallback structure is not detected`() {
        assertFalse(ApprovalPromptDetector.isApprovalPrompt(cardText(), null))
    }

    @Test fun `keyboards with other callbacks are not approval prompts`() {
        val slashConfirm = keyboard(
            listOf(callback("Approve Once", "sc:once:abc"), callback("Always Approve", "sc:always:abc")),
            listOf(callback("Cancel", "sc:cancel:abc"))
        )
        val clarify = keyboard(listOf(callback("1", "cl:9:0")), listOf(callback("Other", "cl:9:other")))
        assertFalse(ApprovalPromptDetector.isApprovalPrompt(cardText(), slashConfirm))
        assertFalse(ApprovalPromptDetector.isApprovalPrompt(cardText(), clarify))
    }

    @Test fun `keyboard without a deny choice is not an approval prompt`() {
        val kb = keyboard(listOf(callback("Allow Once", "ea:once:3"), callback("Always", "ea:always:3")))
        assertFalse(ApprovalPromptDetector.isApprovalPrompt(cardText(), kb))
    }

    @Test fun `url buttons and malformed ea payloads are ignored`() {
        val url = TdApi.InlineKeyboardButton().apply {
            text = "Open"
            type = TdApi.InlineKeyboardButtonTypeUrl("https://example.invalid/ea:once:1")
        }
        val kb = keyboard(listOf(url, callback("x", "ea:once"), callback("y", "ea:deny:abc"), callback("z", "xea:once:1")))
        assertFalse(ApprovalPromptDetector.isApprovalPrompt(cardText(), kb))
    }

    @Test fun `reply keyboard markup is not an inline approval card`() {
        assertFalse(ApprovalPromptDetector.isApprovalPrompt("hello", TdApi.ReplyMarkupRemoveKeyboard()))
    }

    // --- sender gate -----------------------------------------------------

    private fun message(chatId: Long, sender: TdApi.MessageSender?, outgoing: Boolean = false) =
        TdApi.Message().apply {
            this.chatId = chatId
            senderId = sender
            isOutgoing = outgoing
        }

    @Test fun `private chat partner is a candidate sender`() {
        assertEquals(555L, ApprovalPromptDetector.candidateSenderUserId(message(555, TdApi.MessageSenderUser(555))))
    }

    @Test fun `private chat message from a different user is rejected`() {
        assertNull(ApprovalPromptDetector.candidateSenderUserId(message(555, TdApi.MessageSenderUser(999))))
    }

    @Test fun `group sender user is a candidate (bot check happens in the service)`() {
        assertEquals(42L, ApprovalPromptDetector.candidateSenderUserId(message(-100123, TdApi.MessageSenderUser(42))))
    }

    @Test fun `outgoing and chat or anonymous senders are rejected`() {
        assertNull(ApprovalPromptDetector.candidateSenderUserId(message(555, TdApi.MessageSenderUser(555), outgoing = true)))
        assertNull(ApprovalPromptDetector.candidateSenderUserId(message(-100123, TdApi.MessageSenderChat(-100123))))
        assertNull(ApprovalPromptDetector.candidateSenderUserId(message(-100123, null)))
    }
}
