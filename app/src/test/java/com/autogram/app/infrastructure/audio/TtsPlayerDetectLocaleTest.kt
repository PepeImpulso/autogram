package com.autogram.app.infrastructure.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Tests for [TtsPlayer.detectLocale] — the content-based language detector
 * that keeps a Spanish summary from being read by an English voice even
 * when the UI locale is English.
 *
 * detectLocale is a pure function in a companion object, so these tests
 * need no Android context.
 */
class TtsPlayerDetectLocaleTest {

    private val fallback = Locale.ENGLISH

    @Test fun `blank text returns fallback`() {
        assertEquals(fallback, TtsPlayer.detectLocale("", fallback))
        assertEquals(fallback, TtsPlayer.detectLocale("   ", fallback))
    }

    @Test fun `plain ASCII returns fallback`() {
        assertEquals(fallback, TtsPlayer.detectLocale("Hello world, how are you today?", fallback))
    }

    @Test fun `Spanish markers promote to es locale`() {
        val out = TtsPlayer.detectLocale("¿Cómo estás? Está lloviendo mucho.", fallback)
        assertEquals(Locale("es"), out)
    }

    @Test fun `single accent is enough to detect Spanish`() {
        assertEquals(Locale("es"), TtsPlayer.detectLocale("Está bien", fallback))
    }

    @Test fun `Spanish tilde ñ triggers detection`() {
        assertEquals(Locale("es"), TtsPlayer.detectLocale("España", fallback))
    }

    @Test fun `Japanese hiragana wins over fallback`() {
        val out = TtsPlayer.detectLocale("こんにちは", fallback)
        assertEquals(Locale.JAPANESE, out)
    }

    @Test fun `Japanese katakana wins over fallback`() {
        val out = TtsPlayer.detectLocale("カタカナ", fallback)
        assertEquals(Locale.JAPANESE, out)
    }

    @Test fun `Chinese ideographs without kana map to Chinese`() {
        val out = TtsPlayer.detectLocale("你好世界", fallback)
        assertEquals(Locale.CHINESE, out)
    }

    @Test fun `Japanese text with kanji+kana stays Japanese`() {
        // A plausible Japanese sentence mixing kanji and hiragana — the
        // kana detection must win over the CJK ideograph match.
        val out = TtsPlayer.detectLocale("今日は元気ですか", fallback)
        assertEquals(Locale.JAPANESE, out)
    }

    @Test fun `Korean hangul maps to ko`() {
        val out = TtsPlayer.detectLocale("안녕하세요", fallback)
        assertEquals(Locale.KOREAN, out)
    }

    @Test fun `CJK takes priority over Spanish markers in mixed text`() {
        // If for some reason a summary mixes Spanish and CJK, CJK wins
        // because that's the more-specific script signal. Matches current
        // implementation order; this test locks the behaviour in.
        val out = TtsPlayer.detectLocale("Hola 你好 ¿qué tal?", fallback)
        assertEquals(Locale.CHINESE, out)
    }

    @Test fun `fallback preserved when no markers match`() {
        val french = Locale.FRENCH
        assertEquals(french, TtsPlayer.detectLocale("Bonjour tout le monde", french))
    }
}
