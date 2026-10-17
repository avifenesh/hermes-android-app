package dev.avifenesh.hermes

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure UTF-16-to-cell accounting. Android ICU supplies these grapheme boundaries at runtime. */
class TerminalLogicTest {
    // Unit tests isolate grapheme aggregation from Android ICU. Real classification
    // is covered on-device by TerminalUnicodeWidthTest.
    private fun fixtureWidth(cp: Int) = if (cp in setOf(0x754C, 0x20000, 0x1F469, 0x1F468,
        0x1F467, 0x1F466, 0x1F4BB, 0x1F1EE, 0x1F1F1)) 2 else 1
    private fun width(cluster: String, col: Int = 0) = TerminalCells.width(cluster, col, ::fixtureWidth)
    private fun column(text: String, offset: Int, boundaries: List<Int>) =
        TerminalCells.column(text, offset, boundaries, ::fixtureWidth)
    @Test fun asciiAndCjk() {
        val text = "a界b"
        val boundaries = listOf(0, 1, 2, 3)
        assertEquals(0, column(text, 0, boundaries))
        assertEquals(1, column(text, 1, boundaries))
        assertEquals(3, column(text, 2, boundaries))
        assertEquals(4, column(text, 3, boundaries))
    }
    @Test fun combiningMarksStayInTheirCell() {
        val text = "e\u0301x"
        val boundaries = listOf(0, 2, 3)
        assertEquals(0, column(text, 1, boundaries))
        assertEquals(1, column(text, 2, boundaries))
        assertEquals(2, column(text, 3, boundaries))
        assertEquals(0, width("\u0301"))
    }
    @Test fun surrogatePairsAndZwjAreOneWideCluster() {
        val emoji = "👩🏽‍💻"
        val text = "x${emoji}y"
        val boundaries = listOf(0, 1, 1 + emoji.length, text.length)
        for (offset in 1 until 1 + emoji.length)
            assertEquals(1, column(text, offset, boundaries))
        assertEquals(3, column(text, 1 + emoji.length, boundaries))
        assertEquals(4, column(text, text.length, boundaries))
        assertEquals(2, width(emoji))
        assertEquals(2, width("👨‍👩‍👧‍👦"))
    }
    @Test fun emojiPresentationFlagsAndKeycaps() {
        assertEquals(2, width("❤️"))
        assertEquals(2, width("🇮🇱"))
        assertEquals(2, width("1️⃣"))
        assertEquals(2, width("𠀀"))
    }
    @Test fun tabsControlsAndEmptyRows() {
        assertEquals(8, width("\t", 0))
        assertEquals(5, width("\t", 3))
        assertEquals(0, width("\u001b"))
        assertEquals(0, column("", 0, listOf(0)))
    }
}
