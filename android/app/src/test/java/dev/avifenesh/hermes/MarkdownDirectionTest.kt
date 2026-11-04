package dev.avifenesh.hermes

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A markdown block's direction comes from its first strong character, not the device locale. */
class MarkdownDirectionTest {
    private val rtl = LayoutDirection.Rtl
    private val ltr = LayoutDirection.Ltr

    @Test fun hebrewIsRtl() {
        assertEquals(rtl, mdDirection("שלום, זו רשימה קצרה"))
    }

    @Test fun arabicIsRtl() {
        assertEquals(rtl, mdDirection("مرحبا بالعالم"))
    }

    @Test fun englishIsLtr() {
        assertEquals(ltr, mdDirection("Hello world"))
    }

    @Test fun leadingDigitsPunctuationAndEmojiThenHebrewIsRtl() {
        for (s in listOf("12 פריטים", "3.5% הנחה", "(!) שימו לב", "🎉 מזל טוב", "- \"ציטוט\"", "  \t 42 ✓ כן", "#1: ראשון")) {
            assertEquals(s, rtl, mdDirection(s))
        }
    }

    @Test fun leadingDigitsThenEnglishIsLtr() {
        assertEquals(ltr, mdDirection("2026 roadmap"))
        assertEquals(ltr, mdDirection("🎉 Shipped"))
    }

    @Test fun mixedTextFollowsItsFirstStrongCharacter() {
        assertEquals(ltr, mdDirection("Deploy שלב שני"))
        assertEquals(rtl, mdDirection("שלב שני of the deploy"))
        assertEquals(rtl, mdDirection("123 שלום hello"))
    }

    @Test fun noStrongCharacterKeepsTheAmbientDirection() {
        for (s in listOf("", "   ", "123 456", "🎉🎉", "---", "(1) [2]")) assertNull(s, mdDirection(s))
    }

    @Test fun explicitMarksAndIsolatesFollowAndroidFirstStrong() {
        assertEquals(rtl, mdDirection("\u200Fhello"))  // RLM
        assertEquals(ltr, mdDirection("\u200Eשלום"))  // LRM
        assertEquals(rtl, mdDirection("\u2066hello\u2069 שלום"))  // an isolated run is skipped
        assertNull(mdDirection("\u2067שלום\u2069"))
    }

    @Test fun supplementaryRtlScriptIsRtl() {
        assertEquals(rtl, mdDirection("𞤀 adlam"))  // U+1E900 ADLAM CAPITAL ALIF
    }

    @Test fun renderedInlineMarkdownDecides() {
        // Md resolves direction on the rendered text, the same string Compose lays out.
        assertEquals(rtl, mdDirection(inline("**שלום** to the team", Night).text))
        assertEquals(rtl, mdDirection(inline("[קישור](https://example.com/path) here", Night).text))
        assertEquals(ltr, mdDirection(inline("`npm i` ואז הרצה", Night).text))
    }
}
