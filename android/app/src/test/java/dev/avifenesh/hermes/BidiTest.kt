package dev.avifenesh.hermes

import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Right-to-left rules: UI copy follows the layout, content keeps its own direction, embedded content is isolated. */
class BidiTest {
    private fun iso(s: String) = "\u2068$s\u2069"

    @Test fun uiStylesFollowTheLayoutDirection() {
        // Content would lay a Hebrew line that starts with a Latin name out left to right.
        assertEquals(TextDirection.Unspecified, sans(14f).textDirection)
        assertEquals(TextDirection.Unspecified, mono(12f).textDirection)
        assertEquals(TextDirection.Unspecified, serif(46f).textDirection)
    }

    @Test fun contentAndCodeChooseTheirDirection() {
        assertEquals(TextDirection.Content, sans(14f).content().textDirection)
        assertEquals(TextDirection.Ltr, mono(13f).ltr().textDirection)
    }

    @Test fun shortTextBreaksBetweenPhrasesWithoutBalancing() {
        // Phrase breaks keep Japanese words whole; balanced lines would narrow a long row subtitle.
        val lb = sans(13f).short().lineBreak
        assertEquals(LineBreak.WordBreak.Phrase, lb.wordBreak)
        assertEquals(LineBreak.Strategy.Simple, lb.strategy)
    }

    @Test fun isolateWrapsContentInAFirstStrongIsolate() {
        assertEquals(iso("@research list the two follow-ups."), bidiIsolate("@research list the two follow-ups."))
        assertEquals(iso("Hermes Demo"), bidiIsolate("Hermes Demo"))
        assertEquals("", bidiIsolate(""))
    }

    @Test fun isolateBalancesControlsInsideTheContent() {
        // A stray PDI would close the isolate early and let the rest join the sentence; it is dropped.
        assertEquals(iso("ab"), bidiIsolate("a\u2069b"))
        // An isolate the content opens is closed before the outer one.
        assertEquals(iso("a\u2066b\u2069"), bidiIsolate("a\u2066b"))
        // Matched pairs stay as they are.
        assertEquals(iso("a\u2067b\u2069c"), bidiIsolate("a\u2067b\u2069c"))
    }

    @Test fun isolateWrapsEachLineBecauseAParagraphBreakEndsIsolates() {
        assertEquals(iso("one") + "\n" + iso("two"), bidiIsolate("one\ntwo"))
        assertEquals(iso("a\u2066b\u2069") + "\n" + iso("c"), bidiIsolate("a\u2066b\nc"))
        assertEquals(iso("x") + "\n", bidiIsolate("x\n"))
    }

    @Test fun joinIsolatesEveryPart() {
        assertEquals(iso("Hermes Review Sandbox") + " · " + iso("default") + " · " + iso("synthetic-demo"),
            bidiJoin(listOf("Hermes Review Sandbox", "default", "synthetic-demo")))
        assertEquals(iso("Ada") + ", " + iso("Bo"), bidiJoin(listOf("Ada", "Bo"), ", "))
    }

    @Test fun usageLinesIsolateTheModelName() {
        // "synthetic-demo · 2.8K tokens" must read right to left in Hebrew with the model kept whole.
        assertEquals(iso("opus 5.5"), headerLine(JSONObject().put("model", "claude-opus-5-5"), null))
        assertEquals(iso("synthetic-demo"), usageLine(JSONObject().put("model", "synthetic-demo")))
    }

    @Test fun trackingIsDroppedOnlyForScriptsAndroidDrawsUnspaced() {
        assertEquals((-0.4).sp, serif(46f).letterSpacing)
        for (t in listOf("الوارد", "أنا", "النبض", "المنبّه", "١١:٢١ م", "Inbox \u200Fالوارد", "हिन्दी", "\uD805\uDE00", "\uD805\uDC80", "\uD802\uDF80")) {
            assertTrue(t, hasUnspacedScript(t))
            assertEquals(t, TextUnit.Unspecified, serif(46f).trackFor(t).letterSpacing)
        }
        for (t in listOf("Inbox", "הודעות", "受信トレイ", "Eingang", "11:21")) {
            assertFalse(t, hasUnspacedScript(t))
            assertEquals(t, (-0.4).sp, serif(46f).trackFor(t).letterSpacing)
        }
        assertEquals(TextUnit.Unspecified, sans(14f).trackFor("الوارد").letterSpacing)
    }
}
