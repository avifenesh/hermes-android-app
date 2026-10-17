package dev.avifenesh.hermes

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure AnnotatedString checks: no composition, Android URI handling, or clicks. */
class MarkdownInlineTest {
    private val download = "https://github.com/avifenesh/hermes-android-app/releases/download/v0.16.5/Hermes-0.16.5.apk"

    private fun links(text: AnnotatedString) = text.getLinkAnnotations(0, text.length)

    private fun assertLink(text: AnnotatedString, label: String, url: String, start: Int = 0) {
        val ranges = links(text)
        assertEquals(1, ranges.size)
        val range = ranges.single()
        assertEquals(start, range.start)
        assertEquals(start + label.length, range.end)
        assertEquals(label, text.text.substring(range.start, range.end))
        val link = range.item as LinkAnnotation.Url
        assertEquals(url, link.url)
    }

    private fun assertStyle(text: AnnotatedString, start: Int, end: Int, predicate: (SpanStyle) -> Boolean) {
        assertTrue("Missing style over [$start, $end) in ${text.spanStyles}",
            text.spanStyles.any { it.start <= start && it.end >= end && predicate(it.item) })
    }

    @Test fun exactPriorBoldDownloadLinkIsTappable() {
        val label = "Download Hermes 0.16.5 APK"
        val text = inline("**[Download Hermes 0.16.5 APK]($download)**", Night)
        assertEquals(label, text.text)
        assertLink(text, label, download)
        assertStyle(text, 0, label.length) { it.fontWeight == FontWeight.SemiBold }
    }

    @Test fun italicWrappedLinkPreservesOffsetsAndStyle() {
        val text = inline("Get *[the APK]($download)* now.", Night)
        assertEquals("Get the APK now.", text.text)
        assertLink(text, "the APK", download, 4)
        assertStyle(text, 4, 11) { it.fontStyle == FontStyle.Italic }
    }

    @Test fun linkLabelCanContainBoldItalicAndCode() {
        val text = inline("[**Get** *the* `APK`]($download)", Night)
        assertEquals("Get the \u2009APK\u2009", text.text)
        assertLink(text, text.text, download)
        assertStyle(text, 0, 3) { it.fontWeight == FontWeight.SemiBold }
        assertStyle(text, 4, 7) { it.fontStyle == FontStyle.Italic }
        assertStyle(text, 8, text.length) { it.background == Night.s3 && it.fontSize == 0.86.em }
    }

    @Test fun nestedEmphasisAndFormattedLabelKeepOneLink() {
        val text = inline("**Get *[the **APK**]($download)* now**", Night)
        assertEquals("Get the APK now", text.text)
        assertLink(text, "the APK", download, 4)
        assertStyle(text, 0, text.length) { it.fontWeight == FontWeight.SemiBold }
        assertStyle(text, 4, 11) { it.fontStyle == FontStyle.Italic }
        assertStyle(text, 8, 11) { it.fontWeight == FontWeight.SemiBold }
    }

    @Test fun tripleEmphasisKeepsLink() {
        val text = inline("***[APK]($download)***", Night)
        assertEquals("APK", text.text)
        assertLink(text, "APK", download)
        assertStyle(text, 0, 3) { it.fontWeight == FontWeight.SemiBold && it.fontStyle == FontStyle.Italic }
    }

    @Test fun unsafeDestinationsStayVisibleAndInertEvenWithFormattedLabels() {
        for (url in listOf("hermes://pair?token=x", "intent://pair", "file:///sdcard/test", "content://contacts/1", "javascript:alert(1)", "HeRmEs://pair")) {
            assertFalse(url, safeLink(url))
            val text = inline("**[*Open*]($url)**", Night)
            assertEquals("Open ($url)", text.text)
            assertTrue(url, links(text).isEmpty())
            assertStyle(text, 0, 4) { it.fontStyle == FontStyle.Italic }
        }
    }

    @Test fun supportedSchemesKeepTheExistingWhitelist() {
        for (url in listOf("https://example.org", "HTTP://example.org", "mailto:hello@example.org")) {
            assertTrue(url, safeLink(url))
            val text = inline("[**Open**]($url)", Night)
            assertEquals("Open", text.text)
            assertLink(text, "Open", url)
        }
    }

    @Test fun codeSpansNeverAutolinkOrInterpretMarkdown() {
        val code = "https://example.org **bold** [APK]($download)"
        val text = inline("**`$code`**", Night)
        assertEquals("\u2009$code\u2009", text.text)
        assertTrue(links(text).isEmpty())
        assertStyle(text, 0, text.length) { it.background == Night.s3 && it.fontFamily == Mono }
        assertStyle(text, 0, text.length) { it.fontWeight == FontWeight.SemiBold }
    }

    @Test fun doubleBacktickCodeKeepsLiteralBackticksAndNoLinks() {
        val text = inline("``https://example.org `literal` ``", Night)
        assertEquals("\u2009https://example.org `literal` \u2009", text.text)
        assertTrue(links(text).isEmpty())
    }

    @Test fun bareUrlsLeaveSentencePunctuationOutsideTheirRanges() {
        val url = "https://example.org/download?q=apk"
        for (punctuation in listOf(".", ",", ";", ":", "!", "?", "...", "),")) {
            val source = "See $url$punctuation next"
            val text = inline(source, Night)
            assertEquals(source, text.text)
            assertLink(text, url, url, 4)
        }
    }

    @Test fun bareUrlsKeepBalancedParenthesesButNotWrappingParentheses() {
        val url = "https://example.org/a_(b_(c))"
        val source = "See ($url)."
        val text = inline(source, Night)
        assertEquals(source, text.text)
        assertLink(text, url, url, 5)
    }

    @Test fun explicitLinkDestinationsKeepBalancedParentheses() {
        val url = "https://example.org/a_(b_(c))"
        val text = inline("**[Download]($url)**.", Night)
        assertEquals("Download.", text.text)
        assertLink(text, "Download", url)
    }

    @Test fun urlLookingLabelDoesNotCreateNestedLinks() {
        val text = inline("[**https://example.org**]($download)", Night)
        assertEquals("https://example.org", text.text)
        assertLink(text, text.text, download)
    }

    @Test fun themedLinkStylesArePreserved() {
        for (palette in listOf(Night, Day)) {
            val text = inline("**[APK]($download)**", palette)
            assertLink(text, "APK", download)
            val styles = (links(text).single().item as LinkAnnotation.Url).styles!!
            assertEquals(palette.acct, styles.style!!.color)
            assertEquals(TextDecoration.Underline, styles.style!!.textDecoration)
        }
    }

    @Test fun italicContainsNestedBoldAfterWhitespace() {
        // Before: "outer *inner** end*" (the second opening star closed italic).
        val text = inline("*outer **inner** end*", Night)
        assertEquals("outer inner end", text.text)
        assertStyle(text, 0, text.length) { it.fontStyle == FontStyle.Italic }
        assertStyle(text, 6, 11) { it.fontWeight == FontWeight.SemiBold }
        assertFalse(text.spanStyles.any { it.item.fontWeight == FontWeight.SemiBold && (it.start < 6 || it.end > 11) })
    }

    @Test fun boldContainsItalicWithAdjacentClosingDelimiters() {
        // Before: bold consumed the first two final stars, leaving literal stars.
        val text = inline("**outer *inner***", Night)
        assertEquals("outer inner", text.text)
        assertStyle(text, 0, text.length) { it.fontWeight == FontWeight.SemiBold }
        assertStyle(text, 6, 11) { it.fontStyle == FontStyle.Italic }
    }

    @Test fun boldLinkAndAdjacentClosingItalicKeepTheirRanges() {
        val url = "https://example.org"
        val text = inline("**[APK]($url) *inner***", Night)
        assertEquals("APK inner", text.text)
        assertLink(text, "APK", url)
        assertStyle(text, 0, text.length) { it.fontWeight == FontWeight.SemiBold }
        assertStyle(text, 4, 9) { it.fontStyle == FontStyle.Italic }
    }

    @Test fun italicNestedBoldLinksWorkInsideAndOutsideLabels() {
        for (palette in listOf(Night, Day)) {
            val outside = inline("*outer **[inner]($download)** end*", palette)
            assertEquals("outer inner end", outside.text)
            assertLink(outside, "inner", download, 6)
            assertStyle(outside, 0, outside.length) { it.fontStyle == FontStyle.Italic }
            assertStyle(outside, 6, 11) { it.fontWeight == FontWeight.SemiBold }
            val label = inline("[*outer **inner** end*]($download)", palette)
            assertEquals("outer inner end", label.text)
            assertLink(label, label.text, download)
            assertStyle(label, 0, label.length) { it.fontStyle == FontStyle.Italic }
            assertStyle(label, 6, 11) { it.fontWeight == FontWeight.SemiBold }
        }
    }

    @Test fun tripleEmphasisCanNestInsideBoldAndItalic() {
        for (marker in listOf("**", "*")) {
            val text = inline("${marker}outer ***inner*** end$marker", Night)
            assertEquals("outer inner end", text.text)
            assertStyle(text, 6, 11) { it.fontWeight == FontWeight.SemiBold && it.fontStyle == FontStyle.Italic }
            assertStyle(text, 0, text.length) {
                if (marker.length == 2) it.fontWeight == FontWeight.SemiBold else it.fontStyle == FontStyle.Italic
            }
        }
    }

    @Test fun tripleEmphasisLinkCanNestInsideBold() {
        val url = "https://example.org"
        val text = inline("**outer ***[APK]($url)*** end**", Night)
        assertEquals("outer APK end", text.text)
        assertLink(text, "APK", url, 6)
        assertStyle(text, 0, text.length) { it.fontWeight == FontWeight.SemiBold }
        assertStyle(text, 6, 9) { it.fontWeight == FontWeight.SemiBold && it.fontStyle == FontStyle.Italic }
    }

    @Test fun malformedDelimitersHaveBoundedIndexWorkAndStayLiteral() {
        // Deterministic work bound, not a machine-dependent timing threshold:
        // eight linear index passes/visits at most, including failed lookups.
        for (size in listOf(40_000, 80_000, 160_000)) {
            for (source in listOf(
                "[".repeat(size),
                "`".repeat(size),
                "[x](".repeat(size / 4),
                "*x " + "[".repeat(size),
                "*x ".repeat(size / 3),
            )) {
                val index = InlineDelimiters(source)
                assertTrue("${source.length} chars took ${index.indexedSteps} index steps",
                    index.indexedSteps <= 8L * source.length)
                val text = inline(source, Night)
                assertEquals(source, text.text)
                assertTrue(links(text).isEmpty())
                assertTrue(text.spanStyles.isEmpty())
            }
        }
    }

    @Test fun distinctUnmatchedTickRunsNeverRescanOrReinterpretTheirSuffixes() {
        val source = (1..500).joinToString("x") { "`".repeat(it) }
        val index = InlineDelimiters(source)
        assertTrue(index.indexedSteps <= 8L * source.length)
        assertEquals(source, inline(source, Night).text)
        // A failed opener cannot hide a later valid link or cross a newline.
        val text = inline(source + "\n**[APK]($download)**", Night)
        assertEquals(source + "\nAPK", text.text)
        assertLink(text, "APK", download, source.length + 1)
    }

    @Test fun plainAndUnclosedMarkupStayReadable() {
        for (source in listOf("plain text", "**unfinished", "[APK](unfinished", "`unfinished", "")) {
            val text = inline(source, Night)
            assertEquals(source, text.text)
            assertTrue(links(text).isEmpty())
        }
    }
}
