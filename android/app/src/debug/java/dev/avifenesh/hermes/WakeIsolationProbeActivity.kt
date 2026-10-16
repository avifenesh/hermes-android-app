package dev.avifenesh.hermes

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Emulator-only regression probe. Pair with an isolated fixture before running. Never included in release. */
class WakeIsolationProbeActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(Build.PRODUCT.contains("sdk") && Prefs.paired) { "Use a paired Android emulator" }
        val oldTicket = Api.epoch.capture()
        val fixture = JSONObject().put("next", JSONObject().put("alarm_id", "isolation-probe")
            .put("ts", nowS() + 3600))
        Wake.activate(oldTicket)
        Wake.schedule(this, fixture, oldTicket)
        val oldIntent = Wake.intent(this, "fire").putExtra("aid", "isolation-probe")
        Wake.markLocalFire(ticket = oldTicket)
        val pi = PendingIntent.getForegroundService(this, 100, Intent(this, WakeService::class.java).setAction("fire"),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        check(Wake.canExact(this) && pi != null) { "Grant exact alarms before running the probe" }
        Hub.reset()
        check(!Wake.accepts(oldIntent)) { "Old delivered alarm still accepted" }
        check(Wake.localFire == 0L && Wake.overview.value == null && Wake.session.value == null) { "Old wake cache survived" }
        check(PendingIntent.getForegroundService(this, 100, Intent(this, WakeService::class.java).setAction("fire"),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) == null) { "Old alarm PendingIntent survived" }
        lifecycleScope.launch {
            var rejected = false
            try { Wake.request(oldTicket, "/v1/wake/fired", "POST", JSONObject().put("alarm_id", "isolation-probe")) }
            catch (_: CancellationException) { rejected = true }
            check(rejected) { "Old epoch was allowed to send" }
            Wake.activate(Api.epoch.capture())
            setContentView(TextView(this@WakeIsolationProbeActivity).apply {
                text = "WAKE ISOLATION PASS\nOld alarm cancelled\nOld intent rejected\nCached wake state cleared\nOld request rejected"
                textSize = 22f
                setPadding(32, 80, 32, 32)
            })
        }
    }
}
