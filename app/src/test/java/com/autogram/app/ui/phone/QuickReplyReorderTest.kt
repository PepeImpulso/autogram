package com.autogram.app.ui.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickReplyReorderTest {

    @Test
    fun `last shortcut uses header adjusted index instead of crashing`() {
        val items = listOf("a", "b", "c", "d", "e", "f")

        val reordered = reorderShortcutItems(
            items = items,
            fromLazyIndex = 6,
            toLazyIndex = 2
        )

        assertEquals(listOf("a", "f", "b", "c", "d", "e"), reordered)
    }

    @Test
    fun `moving first shortcut to last preserves every item`() {
        val items = listOf("a", "b", "c", "d", "e", "f")

        val reordered = reorderShortcutItems(
            items = items,
            fromLazyIndex = 1,
            toLazyIndex = 6
        )

        assertEquals(listOf("b", "c", "d", "e", "f", "a"), reordered)
    }

    @Test
    fun `footer target clamps to last shortcut`() {
        val items = listOf("a", "b", "c")

        val reordered = reorderShortcutItems(
            items = items,
            fromLazyIndex = 1,
            toLazyIndex = 4
        )

        assertEquals(listOf("b", "c", "a"), reordered)
    }

    @Test
    fun `non shortcut source is ignored`() {
        val items = listOf("a", "b", "c")

        assertEquals(
            items,
            reorderShortcutItems(items, fromLazyIndex = 0, toLazyIndex = 2)
        )
    }
}
