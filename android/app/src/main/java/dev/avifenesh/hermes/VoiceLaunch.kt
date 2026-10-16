package dev.avifenesh.hermes

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.PowerManager
import java.util.UUID

/** Process-local, identity-bound, single-use handoff. Elapsed time is not authority. */
internal class VoiceLaunchGate {
    @Volatile var generation = 0L
        private set
    private var token: String? = null
    private var epoch = -1L

    @Synchronized fun issue(origin: Long): String {
        invalidate()
        epoch = origin
        return UUID.randomUUID().toString().also { token = it }
    }

    @Synchronized fun consume(candidate: String?, current: Long): Boolean {
        val accepted = candidate != null && candidate == token && epoch == current
        invalidate() // Even an unrelated external entry revokes a pending handoff.
        return accepted
    }

    @Synchronized fun invalidate() { token = null; epoch = -1; generation++ }
}

internal data class VoiceTicket(val generation: Long, val epoch: Long, val wake: Boolean)

/** Every async continuation retains its own ticket; a new tap cannot revive old work. */
internal class VoiceSessionGate {
    private var generation = 0L
    var current: VoiceTicket? = null
        private set
    fun begin(epoch: Long, wake: Boolean): VoiceTicket {
        invalidate()
        return VoiceTicket(generation, epoch, wake).also { current = it }
    }
    fun valid(ticket: VoiceTicket?, epoch: Long, wakeEnabled: Boolean): Boolean =
        ticket != null && ticket == current && ticket.epoch == epoch && (!ticket.wake || wakeEnabled)
    fun invalidate() { generation++; current = null }
}

/** Main-thread voice ownership only. Never disposes Root or unrelated saveable drafts. */
internal object VoiceSession {
    val gate = VoiceSessionGate()
    private val stops = linkedSetOf<() -> Unit>()
    fun begin(wake: Boolean, epoch: Long = Api.epoch.capture()): VoiceTicket {
        revoke()
        return gate.begin(epoch, wake)
    }
    fun valid(ticket: VoiceTicket?): Boolean = gate.valid(ticket, Api.epoch.capture(), VoiceLaunch.enabled())
    fun observe(stop: () -> Unit): () -> Unit { stops += stop; return { stops -= stop } }
    fun revoke() {
        gate.invalidate()
        Ui.talkListen.value = false
        stops.toList().forEach { it() }
    }
    fun revokeWake() { if (gate.current?.wake == true) revoke() }
}

internal object VoiceLaunch {
    private const val TOKEN = "dev.avifenesh.hermes.voice_once"
    val gate = VoiceLaunchGate()
    // Hold a strong reference: SharedPreferences stores listeners weakly.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when {
            key == null || key in setOf("base", "device", "token", "e_token") -> {
                gate.invalidate(); VoiceSession.revoke()
            }
            key == "wakeword" && !Prefs.wakeWord -> { gate.invalidate(); VoiceSession.revokeWake() }
        }
    }
    fun init(c: Context) {
        c.getSharedPreferences("hm", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)
        androidx.core.content.ContextCompat.registerReceiver(c, object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    gate.invalidate()
                    VoiceSession.revokeWake()
                }
            }
        }, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun wakeIntent(c: Context): Intent = Intent(c, VoiceLaunchActivity::class.java)
        .putExtra(TOKEN, gate.issue(Api.epoch.capture()))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun unlocked(c: Context): Boolean {
        val keyguard = c.getSystemService(KeyguardManager::class.java)
        return !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked &&
            c.getSystemService(PowerManager::class.java).isInteractive
    }

    fun enabled(): Boolean = Prefs.paired && Prefs.wakeWord

    fun handoff(c: Context, epoch: Long = Api.epoch.capture()): Intent = Intent(c, MainActivity::class.java)
        .putExtra("go", "talk").putExtra(TOKEN, gate.issue(epoch))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun consume(i: Intent?): Boolean {
        val accepted = gate.consume(i?.getStringExtra(TOKEN), Api.epoch.capture())
        i?.removeExtra(TOKEN) // Activity recreation / getIntent replay cannot re-arm capture.
        return accepted
    }
}

/** Only the opt-in detector (or its immutable notification PendingIntent) can enter here. */
class VoiceLaunchActivity : Activity() {
    private var generation = 0L
    private var epoch = -1L
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        epoch = Api.epoch.capture()
        val trusted = VoiceLaunch.consume(intent)
        generation = VoiceLaunch.gate.generation
        // No replay after recreation/process death/opt-out or a machine switch.
        if (savedInstanceState != null || !trusted || !VoiceLaunch.enabled()) {
            finish(); return
        }
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || delivered) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked || keyguard.isDeviceLocked) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { deliver() }
                override fun onDismissCancelled() { finish() }
                override fun onDismissError() { finish() }
            })
        } else deliver()
    }

    override fun onStop() {
        // Allow the system keyguard UI to take focus while unlocking. Leaving this
        // visible request altogether, however, must revoke a later unlock callback.
        if (!delivered) { delivered = true; finish() }
        super.onStop()
    }

    private fun deliver() {
        if (delivered || isFinishing || isDestroyed) return
        delivered = true
        if (generation == VoiceLaunch.gate.generation && epoch == Api.epoch.capture() && VoiceLaunch.enabled() &&
            VoiceLaunch.unlocked(this)) {
            // Forward only our own fixed destination and one-use token; never arbitrary extras.
            runCatching { startActivity(VoiceLaunch.handoff(this, epoch)) }
                .onFailure { VoiceLaunch.gate.invalidate() }
        }
        finish()
    }
}
