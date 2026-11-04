package dev.avifenesh.hermes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AiNoticeTest {
    @Before fun englishStrings() = TestStrings.install()

    @Test fun aiNoticeCoversSourceRiskPrivacyAndReporting() {
        val text = AiNotice.points().joinToString("\n")
        assertTrue(str(R.string.ai_notice_title).isNotBlank())
        assertTrue("replies come from the user's gateway models", text.contains("models") && text.contains("gateway"))
        assertTrue("replies can be wrong or inappropriate", text.contains("wrong") && text.contains("inappropriate"))
        assertTrue("the publisher does not see chats", text.contains("$EDITION_PUBLISHER does not see your chats"))
        assertTrue("how to report", text.contains("Report a problem") && text.contains("long-press"))
    }

    @Test fun aiNoticeShowsOnceAfterPairing() {
        assertTrue(AiNotice.shouldShow(paired = true, seen = false))
        assertFalse(AiNotice.shouldShow(paired = true, seen = true))
        assertFalse(AiNotice.shouldShow(paired = false, seen = false))
    }
}
