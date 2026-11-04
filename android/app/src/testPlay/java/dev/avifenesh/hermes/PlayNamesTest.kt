package dev.avifenesh.hermes

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Play names the app and its publisher as the store listing does. The user's agent keeps its own name. */
class PlayNamesTest {
    @Test fun appAndPublisherNamesMatchTheStoreListing() {
        assertEquals("Herms", APP_NAME)
        assertEquals("Avifen", EDITION_PUBLISHER)
        assertEquals(APP_NAME, EDITION_NAME)
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/play/AndroidManifest.xml"))
        val app = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("the launcher label and in-app name agree", APP_NAME,
            app.getAttributeNS("http://schemas.android.com/apk/res/android", "label"))
    }

    @Test fun reportCopyNamesThePlayPublisher() {
        for (text in listOf(SupportCopy.INTRO, SupportCopy.RECEIPT, SupportCopy.ROW)) {
            assertTrue(text, text.contains("Avifen"))
            assertFalse(text, text.contains("Tiyuvta"))
        }
        assertEquals("Herms issue report", SupportCopy.SUBJECT)
    }
}
