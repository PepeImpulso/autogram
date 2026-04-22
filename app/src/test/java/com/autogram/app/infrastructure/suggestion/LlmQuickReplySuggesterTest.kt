package com.autogram.app.infrastructure.suggestion

import com.autogram.app.domain.repository.SettingsRepository
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the tolerant JSON-array parser. The HTTP path is integration-y
 * and skipped here; what matters is that real-world LLM outputs (markdown
 * fences, leading commentary, mixed quotes) get parsed into a list anyway
 * so we don't unnecessarily fall back to regex on a perfectly good response.
 */
class LlmQuickReplySuggesterTest {

    private lateinit var suggester: LlmQuickReplySuggester

    @Before fun setUp() {
        suggester = LlmQuickReplySuggester(
            settingsRepository = mockk<SettingsRepository>(relaxed = true),
            httpClient = OkHttpClient()
        )
    }

    @Test fun `clean JSON array parses`() {
        val out = suggester.parseStringArray("""["yes", "no", "maybe"]""", limit = 5)
        assertEquals(listOf("yes", "no", "maybe"), out)
    }

    @Test fun `markdown json fence stripped`() {
        val raw = """
            ```json
            ["At home", "On my way"]
            ```
        """.trimIndent()
        val out = suggester.parseStringArray(raw, limit = 5)
        assertEquals(listOf("At home", "On my way"), out)
    }

    @Test fun `bare backtick fence stripped`() {
        val raw = "```\n[\"Sí\", \"No\"]\n```"
        val out = suggester.parseStringArray(raw, limit = 5)
        assertEquals(listOf("Sí", "No"), out)
    }

    @Test fun `LLM preamble before array still extracts`() {
        // Some models add chatty preambles; we should still find the array.
        val raw = """Here are your options: ["Voy", "Tarde", "Cancelo"] hope that helps"""
        val out = suggester.parseStringArray(raw, limit = 5)
        assertEquals(listOf("Voy", "Tarde", "Cancelo"), out)
    }

    @Test fun `limit caps the parsed array`() {
        val out = suggester.parseStringArray("""["a","b","c","d","e"]""", limit = 2)
        assertEquals(listOf("a", "b"), out)
    }

    @Test fun `blank items are dropped`() {
        val out = suggester.parseStringArray("""["", "ok", "  ", "yes"]""", limit = 5)
        assertEquals(listOf("ok", "yes"), out)
    }

    @Test fun `empty input returns empty`() {
        assertTrue(suggester.parseStringArray("", 5).isEmpty())
        assertTrue(suggester.parseStringArray("   ", 5).isEmpty())
    }

    @Test fun `non-array text returns empty`() {
        assertTrue(suggester.parseStringArray("I cannot help with that.", 5).isEmpty())
    }

    @Test fun `malformed JSON returns empty`() {
        assertTrue(suggester.parseStringArray("[unclosed", 5).isEmpty())
    }
}
