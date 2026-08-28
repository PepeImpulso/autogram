package com.autogram.app.infrastructure.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for the platform-length-safe TTS chunker. */
class TtsPlayerChunkingTest {

    @Test fun `short text remains one utterance`() {
        assertEquals(
            listOf("A short reply."),
            TtsPlayer.chunkText("A short reply.", maxLength = 100)
        )
    }

    @Test fun `long text prefers a sentence boundary`() {
        assertEquals(
            listOf("One sentence.", "Two words here. Tail"),
            TtsPlayer.chunkText(
                "One sentence. Two words here. Tail",
                maxLength = 20
            )
        )
    }

    @Test fun `text without a nearby sentence break uses whitespace`() {
        assertEquals(
            listOf("alpha beta", "gamma delta"),
            TtsPlayer.chunkText("alpha beta gamma delta", maxLength = 12)
        )
    }

    @Test fun `single oversized token is hard split`() {
        assertEquals(
            listOf("abcd", "efgh", "ij"),
            TtsPlayer.chunkText("abcdefghij", maxLength = 4)
        )
    }

    @Test fun `every generated chunk respects the platform limit`() {
        val chunks = TtsPlayer.chunkText(
            "First sentence. Second sentence has several words. " +
                "Third sentence is deliberately longer than the limit.",
            maxLength = 24
        )

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 24 })
    }

    @Test fun `blank text creates no utterances`() {
        assertEquals(emptyList<String>(), TtsPlayer.chunkText("   ", maxLength = 20))
    }

    @Test fun `a rejected engine request is retried only once`() {
        assertTrue(TtsPlayer.shouldRetryRejectedRequest(attempt = 0))
        assertEquals(false, TtsPlayer.shouldRetryRejectedRequest(attempt = 1))
    }
}
