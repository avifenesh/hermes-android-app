package dev.avifenesh.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sideload edition keeps its names. */
class FullNamesTest {
    @Test fun namesStayUnchanged() {
        assertEquals("Hermes", APP_NAME)
        assertEquals("Hermes for Android", EDITION_NAME)
        assertEquals("Tiyuvta", EDITION_PUBLISHER)
        assertTrue(SupportCopy.INTRO.startsWith("Voluntary report to Tiyuvta, the app publisher"))
        assertEquals("Hermes issue report", SupportCopy.SUBJECT)
    }
}
