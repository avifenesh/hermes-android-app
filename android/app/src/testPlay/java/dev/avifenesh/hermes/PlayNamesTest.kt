package dev.avifenesh.hermes

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Element

/** Play names the app and its publisher as the store listing does. The user's agent keeps its own name. */
class PlayNamesTest {
    @Before fun englishStrings() = TestStrings.install()

    @Test fun appAndPublisherNamesMatchTheStoreListing() {
        assertEquals("Herms", APP_NAME)
        assertEquals("Avifen", EDITION_PUBLISHER)
        assertEquals(APP_NAME, EDITION_NAME)
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/play/AndroidManifest.xml"))
        val app = manifest.getElementsByTagName("application").item(0) as Element
        val label = app.getAttributeNS("http://schemas.android.com/apk/res/android", "label")
        assertEquals("the launcher label and in-app name agree", APP_NAME,
            if (label.startsWith("@string/")) TestStrings.strings[label.removePrefix("@string/")] else label)
    }

    @Test fun reportCopyNamesThePlayPublisher() {
        val texts = listOf(str(R.string.support_disclosure, EDITION_PUBLISHER), str(R.string.support_received, EDITION_PUBLISHER),
            str(R.string.support_row_sub, EDITION_PUBLISHER))
        for (text in texts) {
            assertTrue(text, text.contains("Avifen"))
            assertFalse(text, text.contains("Tiyuvta"))
        }
        assertEquals("Herms issue report", SupportCopy.SUBJECT)
    }
}
