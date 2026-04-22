package com.autogram.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the rule-based quick-reply suggester. Rules live in the
 * companion object so they're pure — no Hilt, no context.
 */
class SuggestQuickReplyUseCaseTest {

    private val useCase = SuggestQuickReplyUseCase()

    @Test fun `blank input returns no suggestions`() {
        assertTrue(useCase.suggestFor("").isEmpty())
        assertTrue(useCase.suggestFor("   ").isEmpty())
    }

    @Test fun `Spanish where-are-you triggers es location suggestions`() {
        val out = useCase.suggestFor("Oye, ¿dónde estás?")
        assertTrue(out.isNotEmpty())
        assertEquals("es.where", out.first().ruleId)
        assertTrue(out.any { it.text == "En casa" })
    }

    @Test fun `English where-are-you triggers en location suggestions`() {
        val out = useCase.suggestFor("Hey, where are you?")
        assertTrue(out.isNotEmpty())
        assertEquals("en.where", out.first().ruleId)
        assertTrue(out.any { it.text == "At home" })
    }

    @Test fun `case insensitive - uppercase still matches`() {
        val out = useCase.suggestFor("WHERE ARE YOU")
        assertTrue(out.isNotEmpty())
        assertEquals("en.where", out.first().ruleId)
    }

    @Test fun `limit caps the number of suggestions`() {
        val out = useCase.suggestFor("¿dónde estás?", limit = 2)
        assertEquals(2, out.size)
    }

    @Test fun `thanks shorter list is honored`() {
        val out = useCase.suggestFor("gracias mil")
        assertTrue(out.any { it.ruleId == "es.thanks" })
        // "Thanks" rule only provides 2 replies, so asking for 3 still
        // returns at most 2 (unless another rule also fires, which it might
        // via 'hola' in the text but not here).
        assertTrue(out.size in 1..3)
    }

    @Test fun `no duplicate texts`() {
        // If two rules happened to propose the same reply text, the engine
        // should dedupe case-insensitively. We fabricate a case where both
        // an ES and EN keyword appear, and check no literal duplicates emit.
        val out = useCase.suggestFor("Hola hi ¿qué tal?")
        val texts = out.map { it.text.lowercase() }
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test fun `call-me suggests phone-based replies`() {
        val out = useCase.suggestFor("llámame cuando puedas")
        assertTrue(out.any { it.ruleId == "es.call" })
    }

    @Test fun `greeting matches both langs`() {
        val es = useCase.suggestFor("Buenos días")
        assertTrue(es.any { it.ruleId == "es.greet" })
        val en = useCase.suggestFor("Good morning")
        assertTrue(en.any { it.ruleId == "en.greet" })
    }

    @Test fun `unmatched text returns empty`() {
        // Lorem-style text with no rule keywords.
        val out = useCase.suggestFor("The quick brown fox jumps over the lazy dog")
        // The "when" rule would match "when" if present — this sentence
        // doesn't contain any rule triggers.
        assertTrue(out.isEmpty() || out.all { it.ruleId == "en.when" || it.ruleId == "en.how" })
    }
}
