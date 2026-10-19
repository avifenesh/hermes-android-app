package dev.avifenesh.hermes

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class PublisherReportFenceTest {
    @Test fun staleReportCallbackCannotTouchOrDismissANewerDraft() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val previous = Ui.supportRequest.value
            try {
                val old = SupportRequest(Api.epoch.capture(), null, "me")
                val newer = SupportRequest(Api.epoch.capture(), null, "me")
                Ui.supportRequest.value = newer
                var called = false
                assertFalse(Support.act(old) { called = true })
                assertFalse(called)
                assertSame(newer, Ui.supportRequest.value)
            } finally { Ui.supportRequest.value = previous }
        }
    }
}
