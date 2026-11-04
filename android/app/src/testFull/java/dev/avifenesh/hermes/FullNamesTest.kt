package dev.avifenesh.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The sideload edition keeps its names. */
class FullNamesTest {
    @Before fun englishStrings() = TestStrings.install()

    @Test fun namesStayUnchanged() {
        assertEquals("Hermes", APP_NAME)
        assertEquals("Hermes for Android", EDITION_NAME)
        assertEquals("Tiyuvta", EDITION_PUBLISHER)
        assertTrue(str(R.string.support_disclosure, EDITION_PUBLISHER).startsWith("Voluntary report to Tiyuvta, the app publisher"))
        assertEquals("Hermes issue report", SupportCopy.SUBJECT)
        assertEquals("the launcher label and in-app name agree", APP_NAME, TestStrings.strings["app_name"])
    }
}
