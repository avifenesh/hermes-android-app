package dev.avifenesh.hermes

import java.net.URLEncoder
import org.junit.Assert.*
import org.junit.Test

class SupportPayloadTest {
    @Test fun generalReportContainsOnlyTheExplicitNoteAndDiagnostics() {
        assertEquals(
            "What happened:\nMy issue\n\n---\nApp: test\n\nPrepared by me for sending from my own mail app.",
            SupportPayload.body("  My issue  ", null, "App: test"),
        )
    }

    @Test fun quoteIsAbsentUnlessExplicitlyPassedAndIsNotRewritten() {
        val quote = "  Selected text\n💭 Reasoning: sensitive\n\nAnswer  "
        val without = SupportPayload.body("", null, "App: test")
        assertFalse(without.contains("Selected message:"))
        val with = SupportPayload.body("", quote, "App: test")
        assertTrue(with.contains("Selected message:\n$quote\n\nPrepared"))
        assertFalse(with.endsWith(SupportPayload.SHORTENED))
    }

    @Test fun utf8PrefixNeverSplitsAnEmojiOrMultibyteCharacter() {
        val text = "A😀א界Z"
        assertEquals("", SupportPayload.prefixUtf8(text, 0))
        assertEquals("A", SupportPayload.prefixUtf8(text, 4))
        assertEquals("A😀", SupportPayload.prefixUtf8(text, 5))
        assertEquals("A😀א", SupportPayload.prefixUtf8(text, 8))
        assertEquals("A😀א界Z", SupportPayload.prefixUtf8(text, 11))
    }

    @Test fun longNotesAndQuotesShareOneBudgetAndShowShorteningInTheBody() {
        for (unit in listOf("a", "א", "界", "😀", "\n&?=#%")) {
            val body = SupportPayload.body(unit.repeat(10000), unit.repeat(10000), "App: test")
            assertTrue(body.endsWith(SupportPayload.SHORTENED))
            assertTrue(body.toByteArray(Charsets.UTF_8).size <= SupportPayload.BODY_UTF8_LIMIT)
            // Conservative UTF-16 parcel estimate for percent-encoded query text.
            assertTrue(URLEncoder.encode(body, "UTF-8").length * 2 <= 48 * 1024)
            val prefix = body.removeSuffix(SupportPayload.SHORTENED)
            assertFalse(prefix.last().isHighSurrogate())
        }
    }

    @Test fun longSelectedMessageKeepsItsBeginningAndIncludesTheTruncationNotice() {
        val body = SupportPayload.body("Small note", "SELECTED " + "😀".repeat(10000), "App: test")
        assertTrue(body.startsWith("What happened:\nSmall note\n\n---\nApp: test\n\nSelected message:\nSELECTED "))
        assertTrue(body.endsWith(SupportPayload.SHORTENED))
        assertTrue(body.toByteArray(Charsets.UTF_8).size <= SupportPayload.BODY_UTF8_LIMIT)
    }
}
