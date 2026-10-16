package dev.avifenesh.hermes

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.ComposeView

/** Debug-only, no speech capture: exercise real MainActivity ingress before Compose gets a frame. */
class VoiceIngressProbe : Instrumentation() {
    private var isolated = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        isolated = arguments?.getString("isolated") == "yes"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            check(isolated && Build.PRODUCT.contains("sdk")) { "Use an isolated, unlocked, paired emulator; pass -e isolated yes" }
            check(Prefs.paired && !Prefs.wakeWord && VoiceLaunch.unlocked(targetContext)) {
                "Pair to an isolated fixture, disable wake word and unlock BEFORE starting this probe"
            }
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            var failure: Throwable? = null
            var disposals = 0
            val composed = java.util.concurrent.CountDownLatch(1)
            lateinit var root: ComposeView
            lateinit var draft: androidx.compose.runtime.MutableState<String>
            runOnMainSync {
                root = MainActivity::class.java.getDeclaredField("root").apply { isAccessible = true }.get(activity) as ComposeView
                // Keep the real Root plus an unrelated saveable draft sentinel in its composition.
                root.setContent {
                    draft = rememberSaveable { mutableStateOf("initial") }
                    DisposableEffect(Unit) { composed.countDown(); onDispose { disposals++ } }
                    HermesTheme(Ui.theme.intValue) { Root(activity) }
                }
            }
            // Idle handlers can run before Compose's next frame. Await the actual
            // committed composition on the instrumentation thread, never the UI thread.
            check(composed.await(30, java.util.concurrent.TimeUnit.SECONDS)) { "Draft sentinel composition did not become ready" }
            waitForIdleSync()
            runOnMainSync {
                draft.value = "unrelated unsent draft"

                val oldWake = Prefs.wakeWord
                try {
                    check(Prefs.paired && !oldWake && VoiceLaunch.unlocked(activity)) {
                        "Pair only to an isolated fixture; disable wake word and unlock before this probe"
                    }
                    val handle = MainActivity::class.java.getDeclaredMethod("handle", Intent::class.java).apply { isAccessible = true }
                    // Optional so this probe reports the actual unsafe flag on the pre-fix MainActivity.
                    val pending = runCatching { MainActivity::class.java.getDeclaredField("pendingVoiceStart").apply { isAccessible = true } }.getOrNull()
                    fun pendingNow() = pending?.getBoolean(activity) == true
                    fun enter(i: Intent) {
                        handle.invoke(activity, i)
                        check(root.hasComposition && disposals == 0 && draft.value == "unrelated unsent draft") {
                            "Voice revocation disposed unrelated composition/draft state"
                        }
                    }
                    fun silent(label: String) {
                        check(!Ui.talkListen.value && !pendingNow()) { "$label authorized capture" }
                    }
                    fun external() = Intent(activity, MainActivity::class.java).putExtra("go", "talk").putExtra("listen", true)

                    enter(external())
                    silent("External listen=true")
                    check(Ui.stack.lastOrNull() == Route.TalkR) { "External Talk navigation regressed" }
                    Ui.talkListen.value = true
                    enter(Intent(Intent.ACTION_MAIN))
                    silent("Unrelated external entry with stale flag")
                    enter(Intent("dev.avifenesh.hermes.talk").putExtra("listen", true))
                    silent("External action shortcut")

                    val privateEntry = VoiceLaunch.wakeIntent(activity).component!!
                    val info = activity.packageManager.getActivityInfo(ComponentName(activity, VoiceLaunchActivity::class.java), 0)
                    check(!info.exported && privateEntry.className == VoiceLaunchActivity::class.java.name)
                    Prefs.wakeWord = true
                    val internal = VoiceLaunch.handoff(activity)
                    val replay = Intent(internal)
                    enter(internal)
                    check(pendingNow() && !Ui.talkListen.value) { "Trusted unlocked handoff was lost or started before focus gating" }
                    enter(external())
                    silent("External entry after a trusted handoff")
                    enter(replay)
                    silent("Replayed consumed handoff")
                    val superseded = VoiceLaunch.handoff(activity)
                    enter(Intent(Intent.ACTION_MAIN))
                    enter(superseded)
                    silent("Handoff revoked by external entry")
                    val staleMachine = VoiceLaunch.handoff(activity)
                    Api.epoch.invalidate() // Isolated fixture only; simulate reset without touching credentials.
                    enter(staleMachine)
                    silent("Handoff from previous machine epoch")
                    val current = VoiceSession.begin(wake = true)
                    var stopped = false
                    val remove = VoiceSession.observe { stopped = true }
                    enter(external())
                    remove()
                    check(stopped && !VoiceSession.valid(current)) { "External entry did not synchronously revoke voice work" }
                    val pendingEpoch = VoiceSession.begin(wake = true)
                    Api.epoch.invalidate()
                    check(!VoiceSession.valid(pendingEpoch)) { "Pending capture crossed machine epoch" }
                    val focusTicket = VoiceSession.begin(wake = true)
                    activity.onWindowFocusChanged(false)
                    check(!VoiceSession.valid(focusTicket)) { "Focus loss retained wake authority" }
                    check(root.hasComposition && disposals == 0)
                    val optedOut = VoiceSession.begin(wake = true)
                    Prefs.wakeWord = false
                    check(!VoiceSession.valid(optedOut))
                    Prefs.wakeWord = true
                    check(!VoiceSession.valid(optedOut)) { "Opt-in revived an old session" }
                    Prefs.wakeWord = false
                    enter(VoiceLaunch.handoff(activity))
                    silent("Wake-word opt-out")
                    result.putString("stream", "VOICE INGRESS PASS: external extra/action ignored; private handoff preserved; replay/opt-out/epoch/focus rejected; synchronous scoped revocation; unrelated saveable draft composition retained. No speech capture requested.\n")
                } catch (t: Throwable) {
                    failure = t
                } finally {
                    Prefs.wakeWord = oldWake
                    VoiceLaunch.gate.invalidate()
                    Ui.talkListen.value = false
                    // Clear any pending authorization before the main thread can draw or gain focus.
                    runCatching {
                        MainActivity::class.java.getDeclaredMethod("cancelVoiceEntry").apply { isAccessible = true }.invoke(activity)
                    }
                    activity.finish()
                }
            }
            failure?.let { throw it }
            finish(-1, result)
        } catch (t: Throwable) {
            result.putString("stream", "VOICE INGRESS FAIL: ${t.cause ?: t}\n")
            finish(0, result)
        }
    }
}
