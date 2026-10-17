package dev.avifenesh.hermes

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        PhoneService.controlState.value = Prefs.phoneArmed
        Hub.app = this
        Notify.channels(this)
        Wake.init(this)
        VoiceLaunch.init(this)
        WakeListen.channels(this)
        Push.init(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            var n = 0
            override fun onActivityStarted(a: Activity) { if (n++ == 0) { Hub.foreground = true; Hub.connect() } }
            override fun onActivityStopped(a: Activity) { if (--n == 0) { Hub.foreground = false; if (!Prefs.live && !Prefs.phoneArmed) Hub.disconnect() } }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        if (Prefs.paired) schedule(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Img.trimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Img.trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    companion object {
        fun schedule(c: Context) {
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}

/** Push is optional: AOSP devices keep live sync / periodic work without Google services. */
object Push {
    fun available(c: Context): Boolean = runCatching {
        com.google.android.gms.common.GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(c) ==
            com.google.android.gms.common.ConnectionResult.SUCCESS
    }.getOrDefault(false)

    fun init(c: Context) {
        if (!Prefs.paired || !available(c)) return
        val cfg = Prefs.fcmCfg ?: return
        Hub.scope.launch { runCatching { register(c, cfg) } }
    }

    private suspend fun register(c: Context, cfg: String): Boolean {
        if (!Prefs.paired || !available(c)) return false
        val base = Prefs.base
        val device = Prefs.deviceId
        val j = JSONObject(cfg)
        val options = FirebaseOptions.Builder().setApiKey(j.getString("api_key")).setApplicationId(j.getString("app_id"))
            .setProjectId(j.getString("project_id")).setGcmSenderId(j.getString("sender_id")).build()
        val app = FirebaseApp.getApps(c).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?: FirebaseApp.initializeApp(c, options) ?: return false
        // Switching to a different Firebase project needs a process restart. Never report an old token as ready.
        if (app.options != options) return false
        val token = kotlinx.coroutines.withTimeoutOrNull(10_000) {
            kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (cont.isActive) cont.resumeWith(Result.success(if (task.isSuccessful) task.result else null))
                }
            }
        } ?: return false
        if (!Prefs.paired || Prefs.base != base || Prefs.deviceId != device || Prefs.fcmCfg != cfg) return false
        // The callers disable live sync only after token acquisition AND server registration succeed.
        Api.post("/v1/fcm", JSONObject().put("token", token))
        if (Prefs.base != base || Prefs.deviceId != device || Prefs.fcmCfg != cfg) return false
        Prefs.fcmToken = token
        return true
    }

    suspend fun refresh(c: Context): Boolean {
        if (!Prefs.paired || !available(c)) return false
        val base = Prefs.base
        val device = Prefs.deviceId
        val o = Api.get("/v1/push-config")
        if (!Prefs.paired || Prefs.base != base || Prefs.deviceId != device) return false
        if (!o.optBoolean("enabled")) { Prefs.fcmCfg = null; Prefs.fcmToken = null; return false }
        val cfg = o.getJSONObject("config").toString()
        Prefs.fcmCfg = cfg
        return register(c, cfg)
    }
}

class MainActivity : FragmentActivity() {
    private lateinit var root: ComposeView
    private var resumed = false
    private var pendingVoiceStart = false
    private var pendingVoiceTicket: VoiceTicket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Ui.talkListen.value = false
        if (savedInstanceState == null) handle(intent) else VoiceLaunch.consume(null)
        Ui.theme.intValue = Prefs.theme
        Ui.accent.intValue = Prefs.accent
        Ui.paired.value = Prefs.paired
        if (Prefs.paired) LiveService.sync(this)
        root = ComposeView(this).apply {
            setContent { HermesTheme(Ui.theme.intValue) { Root(this@MainActivity) } }
        }
        setContentView(root)
    }

    /** Revoke queued starts and the voice loop, not the app's composition/drafts. */
    private fun cancelVoiceEntry() {
        pendingVoiceStart = false
        pendingVoiceTicket = null
        VoiceSession.revoke()
    }

    private fun startTrustedVoiceIfReady() {
        if (!pendingVoiceStart || !resumed || !hasWindowFocus()) return
        pendingVoiceStart = false
        if (!VoiceSession.valid(pendingVoiceTicket) || !VoiceLaunch.unlocked(this) || Ui.pairLink.value != null ||
            !Ui.paired.value || Ui.composeOpen.value || Ui.stack.lastOrNull() != Route.TalkR) return
        Ui.talkListen.value = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && VoiceSession.gate.current?.wake == true) cancelVoiceEntry()
        if (hasFocus) {
            startTrustedVoiceIfReady()
        }
    }

    override fun onPause() {
        resumed = false
        // A wake handoff may not start (or continue) recording behind an unlock prompt / another app.
        if (pendingVoiceStart || VoiceSession.gate.current?.wake == true) cancelVoiceEntry()
        super.onPause()
    }

    fun requestNotificationsOnce() {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        val prompts = getSharedPreferences("permission_prompts", Context.MODE_PRIVATE)
        if (Prefs.paired && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prompts.getBoolean("notifications_asked", false)) {
            prompts.edit().putBoolean("notifications_asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        startTrustedVoiceIfReady()
        requestNotificationsOnce()
        // Microphone foreground services must be started from a visible activity, not boot/onCreate.
        WakeListen.sync(this)
        // A denied notification/full-screen permission must not make a ringing alarm impossible to reach.
        if (WakeService.running && (Wake.localRinging() || Wake.phase(Wake.session.value) in setOf("ring", "checkin"))) {
            startActivity(Intent(this, WakeActivity::class.java))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
        startTrustedVoiceIfReady()
    }

    private fun handle(i: Intent?) {
        val origin = Api.epoch.capture()
        val trustedVoice = VoiceLaunch.consume(i)
        cancelVoiceEntry() // Every entry, including launcher/share/other routes, clears stale auto-listen.
        i ?: return
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) i.getStringExtra("wwtest")?.let { f ->
            Thread {
                runCatching {
                    WakeWord.load(assets, Prefs.wakeThreshold).use { ww ->
                        val hits = WakeWord.detectWav(ww, java.io.File(f).readBytes())
                        android.util.Log.i("HermesWakeWord", "wwtest engine=microWakeWord-v2 detectionSamples=${hits.joinToString()}")
                    }
                }.onFailure { android.util.Log.e("HermesWakeWord", "wwtest failed", it) }
            }.start()
        }
        i.data?.takeIf { it.scheme == "hermes" }?.let { Ui.pairLink.value = it }
        i.getStringExtra("chat")?.let { Ui.open(it) }
        i.getStringExtra("agent")?.let { Ui.composeOpen.value = false; Ui.push(Route.AgentR(it, i.getStringExtra("agent_name").orEmpty())) }
        when (i.getStringExtra("go") ?: i.action?.substringAfter("dev.avifenesh.hermes.", "")) {
            "talk" -> {
                // Exported intents navigate only. Auto-listen requires the internal one-use handoff.
                pendingVoiceStart = trustedVoice && VoiceLaunch.enabled() && VoiceLaunch.unlocked(this)
                if (pendingVoiceStart) pendingVoiceTicket = VoiceSession.begin(wake = true, epoch = origin)
                Ui.composeOpen.value = false
                if (Ui.stack.lastOrNull() != Route.TalkR) Ui.push(Route.TalkR)
            }
            "compose" -> { Ui.stack.clear(); Ui.composeOpen.value = true }
            "fleet" -> { Ui.stack.clear(); Ui.tab.value = "pulse" }
        }
        if (i.action == Intent.ACTION_SEND) {
            try {
                val u = IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                u?.let { ExternalShare.validate(this, it, i.flags) }
                Ui.share.value = Share(i.getStringExtra(Intent.EXTRA_TEXT), u, i.type, i.flags)
                Ui.composeOpen.value = true
            } catch (e: Exception) {
                Ui.share.value = null
                Ui.say("Cannot attach shared file: ${e.message ?: "invalid share"}")
            }
        }
    }
}
