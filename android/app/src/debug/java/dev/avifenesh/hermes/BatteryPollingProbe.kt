package dev.avifenesh.hermes

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.delay
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Debug only. A retained composition must stop UI-owned work when its activity stops. */
class BatteryPollingActivity : FragmentActivity() {
    val ticks = AtomicInteger()
    val starts = AtomicInteger()
    val stops = AtomicInteger()
    val active = AtomicInteger()
    val peak = AtomicInteger()
    val stopped = CountDownLatch(1)
    val key = mutableStateOf(0)
    lateinit var root: ComposeView
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        root = ComposeView(this).apply {
            setContent {
                VisibleEffect(key.value) {
                    starts.incrementAndGet()
                    peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    try {
                        while (true) { ticks.incrementAndGet(); delay(25) }
                    } finally { active.decrementAndGet(); stops.incrementAndGet() }
                }
            }
        }
        setContentView(root)
    }
    override fun onStop() { super.onStop(); stopped.countDown() }
}

/** Explicit opt-in; never run against a physical phone or live pairing. */
class BatteryPollingProbe : Instrumentation() {
    private var isolated = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        isolated = arguments?.getString("isolated") == "yes"
        start()
    }
    private fun await(label: String, ready: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (!ready()) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Timed out: $label" }
            Thread.sleep(20)
        }
    }
    override fun onStart() {
        val result = Bundle()
        var activity: BatteryPollingActivity? = null
        try {
            check(isolated && Build.PRODUCT.contains("sdk") && !Prefs.paired) { "Requires an unpaired disposable emulator and -e isolated yes" }
            val intent = Intent(targetContext, BatteryPollingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val a = startActivitySync(intent) as BatteryPollingActivity
            activity = a
            await("foreground polling") { a.ticks.get() >= 3 }
            runOnMainSync { check(a.moveTaskToBack(true)) }
            check(a.stopped.await(10, TimeUnit.SECONDS)) { "Activity never stopped" }
            // Give asynchronous lifecycle cancellation time to complete, then sample a full interval.
            Thread.sleep(150)
            val before = a.ticks.get()
            Thread.sleep(350)
            check(a.ticks.get() == before && a.active.get() == 0) {
                "Background polling continued: ticks=$before->${a.ticks.get()}, active=${a.active.get()}"
            }
            runOnMainSync { check(a.root.hasComposition) { "Test lost the retained composition" } }
            targetContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            await("resume polling") { a.ticks.get() > before && a.active.get() == 1 }
            val starts = a.starts.get()
            runOnMainSync { a.key.value++ }
            await("key restart") { a.starts.get() > starts }
            check(a.peak.get() == 1) { "Overlapping pollers: ${a.peak.get()}" }
            runOnMainSync { a.root.disposeComposition() }
            await("dispose cancellation") { a.active.get() == 0 }
            check(a.starts.get() == a.stops.get()) { "Unreleased work" }
            result.putString("stream", "BATTERY POLLING PASS: foreground active; retained stopped composition idle; resume restarts; key change cancels old work; no overlap; disposal releases all work. starts=${a.starts.get()} stops=${a.stops.get()} backgroundTicks=0\n")
            finish(-1, result)
        } catch (t: Throwable) {
            result.putString("stream", "BATTERY POLLING FAIL: $t\n")
            finish(0, result)
        } finally { activity?.let { a -> runOnMainSync { a.finish() } } }
    }
}
