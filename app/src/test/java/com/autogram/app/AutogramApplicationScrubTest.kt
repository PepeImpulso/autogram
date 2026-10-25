package com.autogram.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Tests for the Sentry PII scrubber. The function is pure — no Android
 * context, no Sentry SDK calls — so it's directly unit-testable.
 *
 * Why this matters: every Timber.e()/.w() call in the app gets relayed to
 * Sentry as event/breadcrumb. If the original message contains a Telegram
 * chat id or a phone number it ends up in the dashboard. The scrubber's
 * job is to hide those before they leave the device. These tests pin the
 * patterns so a future tweak to the regex can't quietly let PII slip.
 */
class AutogramApplicationScrubTest {

    @Test fun `phone number with country code redacted`() {
        val scrubbed = AutogramApplication.scrubPii("Call +34 612 345 678 to verify")
        assertFalse("phone digits leak", scrubbed.contains("612"))
        assertFalse(scrubbed.contains("345"))
        assertEquals("Call [phone] to verify", scrubbed)
    }

    @Test fun `phone number without country code redacted`() {
        val scrubbed = AutogramApplication.scrubPii("dial 555-867-5309 now")
        assertFalse(scrubbed.contains("5309"))
        assertEquals("dial [phone] now", scrubbed)
    }

    @Test fun `Telegram chat id redacted`() {
        // Telegram chat IDs are typically 9-13 digits, sometimes negative.
        val scrubbed = AutogramApplication.scrubPii("send to chat 1234567890 failed")
        assertFalse(scrubbed.contains("1234567890"))
        assertEquals("send to chat [id] failed", scrubbed)
    }

    @Test fun `negative chat id (forum supergroup style) redacted`() {
        val scrubbed = AutogramApplication.scrubPii("topic in -100123456789 missing")
        assertFalse(scrubbed.contains("100123456789"))
        assertEquals("topic in [id] missing", scrubbed)
    }

    @Test fun `pure digit id is not mistaken for a phone`() {
        // The order in scrubPii is LONG_ID first, then PHONE — so a chat id
        // never gets misclassified as a phone (regression caught by tests).
        val scrubbed = AutogramApplication.scrubPii("send to chat 1234567890 ok")
        assertEquals("send to chat [id] ok", scrubbed)
    }

    @Test fun `short numbers like timeouts are kept`() {
        val scrubbed = AutogramApplication.scrubPii("retry after 30 seconds, attempt 4")
        assertEquals("retry after 30 seconds, attempt 4", scrubbed)
    }

    @Test fun `version strings are not mistaken for PII`() {
        val scrubbed = AutogramApplication.scrubPii("autogram@1.15.0+38 launched")
        // 1.15.0 is too short to match the LONG_ID pattern (needs ≥9 digits).
        assertEquals("autogram@1.15.0+38 launched", scrubbed)
    }

    @Test fun `message ID UUIDs are unaffected`() {
        // UUIDs use letters too, so the LONG_ID regex (which is digits-only)
        // doesn't touch them — that's intentional, UUIDs aren't PII.
        val scrubbed = AutogramApplication.scrubPii(
            "playing message e35ea36a-a16e-4a56-b0b3-d3e3eef0c8b2"
        )
        assertEquals(
            "playing message e35ea36a-a16e-4a56-b0b3-d3e3eef0c8b2",
            scrubbed
        )
    }

    @Test fun `mixed phone and chat id are both redacted`() {
        val scrubbed = AutogramApplication.scrubPii(
            "user +1 555 123 4567 sent to 1234567890"
        )
        assertFalse(scrubbed.contains("4567"))
        assertFalse(scrubbed.contains("1234567890"))
    }

    @Test fun `empty string passes through`() {
        assertEquals("", AutogramApplication.scrubPii(""))
    }

    @Test fun `pure non-numeric text passes through unchanged`() {
        val s = "TDLib decryption failed for unknown reason"
        assertEquals(s, AutogramApplication.scrubPii(s))
    }
    @Test fun `email address redacted`() {
        val scrubbed = AutogramApplication.scrubPii("login as jane.doe+tg@example.co.uk failed")
        assertFalse(scrubbed.contains("jane"))
        assertEquals("login as [email] failed", scrubbed)
    }

    @Test fun `telegram username redacted`() {
        val scrubbed = AutogramApplication.scrubPii("resolved @john_smith99 to chat")
        assertFalse(scrubbed.contains("john"))
        assertEquals("resolved [username] to chat", scrubbed)
    }

    @Test fun `object hash codes and short handles are not usernames`() {
        // "Foo@1a2b3c" has a word char before @; "@abc" is below the
        // 5-char Telegram minimum.
        val s = "state=VoiceState@1a2b3c tag @abc"
        assertEquals(s, AutogramApplication.scrubPii(s))
    }
}
