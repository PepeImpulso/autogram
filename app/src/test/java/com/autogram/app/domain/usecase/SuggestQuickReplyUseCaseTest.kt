package com.autogram.app.domain.usecase

import com.autogram.app.infrastructure.suggestion.LlmQuickReplySuggester
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the rule-based quick-reply suggester. The rules live in the
 * companion object so the matching is pure — for these tests we mock the
 * LLM suggester to return empty so we exercise the regex fallback path.
 *
 * One test covers the LLM-success branch to lock in that LLM results win
 * over regex when both would have matched.
 */
class SuggestQuickReplyUseCaseTest {

    private lateinit var llm: LlmQuickReplySuggester
    private lateinit var useCase: SuggestQuickReplyUseCase

    @Before fun setUp() {
        llm = mockk(relaxed = true)
        // Default: LLM returns nothing → tests exercise the regex fallback.
        coEvery { llm.suggest(any(), any()) } returns emptyList()
        useCase = SuggestQuickReplyUseCase(llm)
    }

    @Test fun `blank input returns no suggestions`() = runTest {
        assertTrue(useCase.suggestFor("").isEmpty())
        assertTrue(useCase.suggestFor("   ").isEmpty())
    }

    @Test fun `Spanish where-are-you triggers es location suggestions`() = runTest {
        val out = useCase.suggestFor("Oye, ¿dónde estás?")
        assertTrue(out.isNotEmpty())
        assertEquals("es.where", out.first().ruleId)
        assertTrue(out.any { it.text == "En casa" })
    }

    @Test fun `English where-are-you triggers en location suggestions`() = runTest {
        val out = useCase.suggestFor("Hey, where are you?")
        assertTrue(out.isNotEmpty())
        assertEquals("en.where", out.first().ruleId)
        assertTrue(out.any { it.text == "At home" })
    }

    @Test fun `case insensitive - uppercase still matches`() = runTest {
        val out = useCase.suggestFor("WHERE ARE YOU")
        assertTrue(out.isNotEmpty())
        assertEquals("en.where", out.first().ruleId)
    }

    @Test fun `limit caps the number of suggestions`() = runTest {
        val out = useCase.suggestFor("¿dónde estás?", limit = 2)
        assertEquals(2, out.size)
    }

    @Test fun `thanks shorter list is honored`() = runTest {
        val out = useCase.suggestFor("gracias mil")
        assertTrue(out.any { it.ruleId == "es.thanks" })
        assertTrue(out.size in 1..3)
    }

    @Test fun `no duplicate texts`() = runTest {
        val out = useCase.suggestFor("Hola hi ¿qué tal?")
        val texts = out.map { it.text.lowercase() }
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test fun `call-me suggests phone-based replies`() = runTest {
        val out = useCase.suggestFor("llámame cuando puedas")
        assertTrue(out.any { it.ruleId == "es.call" })
    }

    @Test fun `greeting matches both langs`() = runTest {
        val es = useCase.suggestFor("Buenos días")
        assertTrue(es.any { it.ruleId == "es.greet" })
        val en = useCase.suggestFor("Good morning")
        assertTrue(en.any { it.ruleId == "en.greet" })
    }

    @Test fun `unmatched text returns empty`() = runTest {
        val out = useCase.suggestFor("The quick brown fox jumps over the lazy dog")
        assertTrue(out.isEmpty() || out.all { it.ruleId == "en.when" || it.ruleId == "en.how" })
    }

    @Test fun `LLM success wins over regex`() = runTest {
        // When the LLM returns concrete suggestions, the regex shouldn't run.
        // We pick an input that ALSO matches a regex ("dónde estás?") so we
        // can prove LLM took precedence.
        coEvery { llm.suggest("¿dónde estás?", any()) } returns
            listOf("En la oficina", "Saliendo ya")
        val out = useCase.suggestFor("¿dónde estás?")
        assertEquals(2, out.size)
        assertEquals("llm", out.first().ruleId)
        assertEquals("En la oficina", out[0].text)
        assertEquals("Saliendo ya", out[1].text)
    }

    @Test fun `LLM empty falls back to regex`() = runTest {
        // Default mock already returns empty, so this just asserts the
        // fallback branch is reachable end-to-end.
        coEvery { llm.suggest(any(), any()) } returns emptyList()
        val out = useCase.suggestFor("¿dónde estás?")
        assertTrue(out.isNotEmpty())
        assertEquals("es.where", out.first().ruleId)
    }

    @Test fun `LLM throwing falls back silently`() = runTest {
        coEvery { llm.suggest(any(), any()) } throws RuntimeException("network")
        val out = useCase.suggestFor("¿dónde estás?")
        assertTrue(out.isNotEmpty())
        assertEquals("es.where", out.first().ruleId)
    }

    @Test fun `LLM dedupes case-insensitively`() = runTest {
        coEvery { llm.suggest(any(), any()) } returns
            listOf("OK", "ok", "Vale")
        val out = useCase.suggestFor("¿vale?", limit = 5)
        // "OK" and "ok" must collapse to one entry.
        val texts = out.map { it.text.lowercase() }
        assertEquals(texts.size, texts.toSet().size)
    }
}
