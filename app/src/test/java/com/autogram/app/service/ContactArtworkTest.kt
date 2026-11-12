package com.autogram.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactArtworkTest {

    @Test fun `initial is the uppercased first character`() {
        assertEquals("M", ContactArtwork.initialOf("  merlin"))
    }

    @Test fun `initial keeps a whole emoji or surrogate pair`() {
        assertEquals("😀", ContactArtwork.initialOf("😀 Team"))
    }

    @Test fun `blank name falls back to a question mark`() {
        assertEquals("?", ContactArtwork.initialOf("   "))
    }
}
