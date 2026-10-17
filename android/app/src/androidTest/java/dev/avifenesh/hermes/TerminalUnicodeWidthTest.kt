package dev.avifenesh.hermes

import org.junit.Assert.assertEquals
import org.junit.Test

/** Real platform ICU classification and grapheme boundaries, not JVM Android stubs. */
class TerminalUnicodeWidthTest {
    @Test fun emojiOutsideAstralEmojiBlockAndNarrowSymbols() {
        assertEquals(2, TerminalCells.width("✨"))
        assertEquals(2, TerminalCells.width("⚡"))
        assertEquals(1, TerminalCells.width("🜁"))
        assertEquals(1, TerminalCells.width("✻"))
    }
    @Test fun actualIcuClustersMapToTerminalColumns() {
        for ((prefix, width) in listOf("界" to 2, "é" to 1, "👩🏽‍💻" to 2, "✨" to 2, "🜁" to 1)) {
            val line = prefix + "A"
            assertEquals(width, TerminalCells.column(line, prefix.length, terminalGraphemes(line)))
        }
    }
}
