package com.autogram.app.ui.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadableReleaseNotesTest {

    @Test
    fun `joins hard-wrapped lines and strips markdown markers`() {
        val md = """
            # v1.2.3 — Title 🚗

            Intro that is
            wrapped here.

            ## 🐛 Fixed

            - **Bold lead.** Text that
              continues here.
            - Second `item`.
        """.trimIndent()
        assertEquals(
            "v1.2.3 — Title 🚗\n\nIntro that is wrapped here.\n\n🐛 Fixed\n\n" +
                "• Bold lead. Text that continues here.\n\n• Second item.",
            readableReleaseNotes(md)
        )
    }

    @Test
    fun `keeps numbered lists as separate items`() {
        assertEquals("1. One\n\n2. Two", readableReleaseNotes("1. One\n2. Two"))
    }
}
