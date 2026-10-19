package dev.avifenesh.hermes

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Debug-only REAL SupportDialog with a fake sender; never calls the publisher or gateway.
 * Requires an explicitly acknowledged isolated, loopback-paired fixture, not a user's pairing.
 * adb shell am start -n <debug-package>/.PublisherReportProbeActivity --ez isolated_fixture true
 * Optional --es result success (default error), --el delay_ms 3000, --ez selected true.
 * Calls remains 0 through opening, typing, inclusion toggling, Review and Edit. Only Send
 * increments it. Error retries preserve the displayed ID; editing text changes it. During
 * delay, duplicate taps stay at 1; dismiss increments Cancelled and cannot display Received.
 * No real email tap is needed. Exercise route/epoch fencing separately with Android tests.
 */
class PublisherReportProbeActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loopback = runCatching { Uri.parse(Prefs.base).host in setOf("127.0.0.1", "localhost", "10.0.2.2") }.getOrDefault(false)
        if (!intent.getBooleanExtra("isolated_fixture", false) || !loopback || !Prefs.paired) {
            finish(); return
        }
        Ui.paired.value = true
        Ui.pairLink.value = null
        Ui.stack.clear()
        Ui.tab.value = "me"
        val quote = if (intent.getBooleanExtra("selected", false)) "SYNTHETIC SELECTED MESSAGE - not included by default" else null
        val request = SupportRequest(Api.epoch.capture(), null, "me", quote)
        Ui.supportRequest.value = request
        setContent {
            HermesTheme(Ui.theme.intValue) {
                var calls by remember { mutableStateOf(0) }
                var cancelled by remember { mutableStateOf(0) }
                var lastId by remember { mutableStateOf("none") }
                var quoteSent by remember { mutableStateOf(false) }
                Column {
                    Text("FAKE SENDER - no report network")
                    Text("Calls: $calls; Cancelled: $cancelled")
                    Text("Last ID: $lastId; Quote sent: $quoteSent")
                    Btn("Reopen synthetic report") { Ui.supportRequest.value = request }
                }
                if (Ui.supportRequest.value?.id == request.id) {
                    SupportDialog(request, sendReport = { draft ->
                        calls++
                        lastId = draft.id
                        quoteSent = draft.text.contains("SYNTHETIC SELECTED MESSAGE")
                        try {
                            delay(intent.getLongExtra("delay_ms", 3000L).coerceIn(0L, 60000L))
                            if (intent.getStringExtra("result") != "success") throw IOException("Synthetic failure")
                        } catch (e: CancellationException) {
                            cancelled++
                            throw e
                        }
                    })
                }
            }
        }
    }
}
