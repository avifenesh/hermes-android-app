package dev.avifenesh.hermes

import android.content.Intent
import android.view.Choreographer
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The wake-word switch while its notification's Turn off runs. That action clears the preference inside
 * WakeListenService and reports "off". The panel kept its own copy of the preference, so with Me > This phone > Voice
 * open the switch stayed on and read "Starting…" although nothing was listening. This sends the notification's own
 * intent to the service, while listening and while starting, and reads the switch from the accessibility tree.
 */
class WakeWordPanelTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    @Test fun turnOffWhileListeningTurnsTheSwitchOff() = turnOff("listening", R.string.phone_settings_wake_listening)

    /** The service posts its notification before the detector reports "listening", so Turn off can come while the
     *  panel reads "Starting…" and the state is still "off". The stop writes "off" over "off", which a StateFlow drops. */
    @Test fun turnOffWhileStartingTurnsTheSwitchOff() = turnOff("off", R.string.phone_settings_wake_starting)

    private fun turnOff(state: String, shown: Int) {
        val ctx = ins.targetContext
        val was = Prefs.wakeWord
        ins.runOnMainSync { Prefs.wakeWord = true; WakeListen.state.value = state }
        val act = ins.startActivitySync(Intent(ctx, WakeWordPanelProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            awaitSwitch(true, ctx.getString(shown))
            // The notification's Turn off action: the same intent its PendingIntent carries.
            ctx.startService(Intent(ctx, WakeListenService::class.java).setAction("stop"))
            awaitSwitch(false, ctx.getString(R.string.phone_settings_wake_off_sub))
        } finally {
            ins.runOnMainSync { act.finish(); Prefs.wakeWord = was; WakeListen.state.value = "off" }
            ins.waitForIdleSync()
        }
    }

    /** Waits up to 5 s for the switch to read [checked] with [sub] under its title; fails with what it last saw.
     *  isChecked is deprecated from API 36; its replacement does not exist on the API 31 to 35 devices this runs on. */
    @Suppress("DEPRECATION")
    private fun awaitSwitch(checked: Boolean, sub: String) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var seen = "no window"
        while (System.nanoTime() < until) {
            frame()
            val root = ins.uiAutomation.rootInActiveWindow ?: continue
            val nodes = mutableListOf<AccessibilityNodeInfo>().also { collect(root, it) }
            val switch = nodes.firstOrNull { it.isCheckable }
            val texts = nodes.mapNotNull { it.text?.toString() }
            seen = "switch ${switch?.isChecked}, texts $texts"
            if (switch != null && switch.isChecked == checked && sub in texts) return
        }
        fail("expected switch $checked with \"$sub\"; saw $seen")
    }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        out += n
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, out) }
    }

    private fun frame() {
        val done = CountDownLatch(1)
        ins.runOnMainSync { Choreographer.getInstance().postFrameCallback { done.countDown() } }
        assertTrue("frame callback", done.await(5, TimeUnit.SECONDS))
    }
}
