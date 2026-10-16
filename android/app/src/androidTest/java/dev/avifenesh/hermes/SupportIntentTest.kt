package dev.avifenesh.hermes

import android.content.Intent
import android.net.MailTo
import android.os.Parcel
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Real Android Uri semantics: an opaque mailto must preserve its recipient. */
class SupportIntentTest {
    @Test fun recipientSubjectAndPreviewBodyReachMailAppUnchanged() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val body = "Line one\nעברית 😀 &?=#%+ / selected message\nLine three"
        val intent = Support.intent(ctx, body)
        val mail = MailTo.parse(intent.dataString)
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("hello@tiyuvta.ai", mail.to)
        assertArrayEquals(arrayOf("hello@tiyuvta.ai"), intent.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals(ctx.getString(R.string.report_subject), intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(body, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(mail.cc)
        assertNull(mail.body)
        assertFalse(intent.dataString.orEmpty().contains('?'))
        assertFalse(intent.hasExtra(Intent.EXTRA_CC))
        assertFalse(intent.hasExtra(Intent.EXTRA_BCC))
    }

    @Test fun boundedUnicodePayloadFitsTheIntentBudget() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val body = SupportPayload.body("😀&?=%".repeat(10000), "private quote", "App: test")
        val intent = Support.intent(ctx, body)
        val parcel = Parcel.obtain()
        try {
            intent.writeToParcel(parcel, 0)
            assertTrue(parcel.dataSize() <= SupportPayload.INTENT_BYTES_LIMIT)
        } finally { parcel.recycle() }
        assertEquals(body, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("hello@tiyuvta.ai", MailTo.parse(intent.dataString).to)
    }
}
