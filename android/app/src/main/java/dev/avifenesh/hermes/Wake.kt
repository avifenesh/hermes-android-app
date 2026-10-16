package dev.avifenesh.hermes

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.TimeZone
import java.util.UUID
import kotlin.random.Random

/** Wake alarm state on the phone. The server owns the session; the phone rings, shows the proofs and keeps a local fallback alarm. */
object Wake {
    const val CH = "wake"
    private lateinit var sp: SharedPreferences
    private var enabled = false
    private var blockedBinding: String? = null
    val generation = MutableStateFlow(0L)
    val overview = MutableStateFlow<JSONObject?>(null)
    val session = MutableStateFlow<JSONObject?>(null)
    val offline = MutableStateFlow(false)
    @Volatile var quietUntil = 0L
    @Volatile var localFire = 0L
    @Volatile var localOk = false

    fun init(c: Context) = synchronized(Api.epoch) {
        sp = c.getSharedPreferences("wake", Context.MODE_PRIVATE)
        // Legacy/unowned caches must not become alarms on whichever machine is active at boot.
        if (!Prefs.paired || sp.getString("binding", null) != binding()) reset(c)
        enabled = Prefs.paired
        localFire = sp.getLong("lf", 0)
        localOk = sp.getBoolean("lok", false)
        overview.value = sp.getString("ov", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, "Wake alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "The wake alarm and its check-ins"; setSound(null, null); enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC; setBypassDnd(true)
            })
    }

    // A persisted nonce survives process death, unlike Api.epoch; its binding includes the pairing
    // token so re-pairing the same server/device cannot revive an old alarm or offline session.
    private fun binding() = Api.sha256(org.json.JSONArray(listOf(Prefs.base, Prefs.deviceId, Prefs.token)).toString().toByteArray())

    /** Re-enable only after the target credentials have been committed. */
    internal fun activate(ticket: Long) = Api.epoch.commit(ticket) { enabled = Prefs.paired }

    private fun requireActive() {
        // New pairing bootstraps through Main.refresh, without afterPair. Its atomic credential
        // commit changes this binding; the reset/unpair gap still has the blocked old binding.
        if (!enabled && Prefs.paired && binding() != blockedBinding) enabled = true
        if (!enabled || !Prefs.paired) throw CancellationException("Wake pairing changed")
    }

    private fun owner(): String {
        requireActive()
        val binding = binding()
        check(sp.getString("binding", null).let { it == null || it == binding }) { "Wake identity changed without reset" }
        return sp.getString("owner", null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString("binding", binding).putString("owner", it).apply()
        }
    }

    internal fun accepts(i: Intent?): Boolean = enabled && Prefs.paired && i != null &&
        sp.getString("binding", null) == binding() &&
        i.getStringExtra("wake_owner")?.let { it == sp.getString("owner", null) } == true

    internal fun intent(c: Context, action: String): Intent =
        Intent(c, WakeService::class.java).setAction(action).putExtra("wake_owner", owner())

    private fun cancelAlarm(c: Context, code: Int, action: String) {
        PendingIntent.getForegroundService(c, code, Intent(c, WakeService::class.java).setAction(action),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            am(c).cancel(it)
            it.cancel()
        }
    }

    /** Called under Api.epoch by Hub.reset, before any credentials change. */
    fun reset(c: Context) = synchronized(Api.epoch) {
        if (!::sp.isInitialized) sp = c.getSharedPreferences("wake", Context.MODE_PRIVATE)
        // Block new work even in the gap between reset and clearing/replacing credentials.
        blockedBinding = if (Prefs.paired) binding() else null
        enabled = false
        generation.value = Api.epoch.capture()
        // Clear ownership first, including already-delivered intents that cancellation cannot recall.
        sp.edit().clear().commit()
        overview.value = null; session.value = null; offline.value = false
        quietUntil = 0; localFire = 0; localOk = false
        listOf(100 to "fire", 102 to "check").forEach { (code, action) ->
            runCatching { cancelAlarm(c, code, action) }.onFailure { Log.w("hermes", "wake alarm cancellation failed", it) }
        }
        WakeService.reset(c)
    }

    fun touch(ms: Long = 90_000) { quietUntil = System.currentTimeMillis() + ms }

    fun markLocalFire(ok: Boolean = false, ticket: Long = Api.epoch.capture()) = Api.epoch.commit(ticket) {
        owner()
        if (!ok) localFire = System.currentTimeMillis()
        localOk = ok
        sp.edit().putLong("lf", localFire).putBoolean("lok", ok).apply()
    }

    /** True when the local alarm fired recently and nothing (server or offline puzzle) has cleared it yet. */
    fun localRinging(): Boolean = !localOk && localFire > 0 && System.currentTimeMillis() - localFire < 90 * 60_000

    /** Build the signed request under the originating epoch, not after a retry switches machines. */
    internal suspend fun request(ticket: Long, path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        currentCoroutineContext().ensureActive()
        val req = Api.epoch.commit(ticket) {
            requireActive()
            Api.request(path).method(method, if (method == "GET" || method == "HEAD") null else (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType())).build()
        }
        val result = Api.http.newCall(req).awaitBody { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw ApiError(response.code,
                runCatching { JSONObject(text).str("error") }.getOrNull().orEmpty().ifBlank { "HTTP ${response.code}" })
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
        currentCoroutineContext().ensureActive()
        return Api.epoch.commit(ticket) { result }
    }

    suspend fun refresh(c: Context, ticket: Long = Api.epoch.capture()): JSONObject {
        val o = request(ticket, "/v1/wake?tz=" + Uri.encode(TimeZone.getDefault().id))
        return Api.epoch.commit(ticket) {
            owner()
            sp.edit().putString("ov", o.toString()).apply()
            overview.value = o
            session.value = o.optJSONObject("session")
            offline.value = false
            schedule(c, o, ticket)
            o
        }
    }

    suspend fun pull(ticket: Long = Api.epoch.capture()): JSONObject? {
        val o = request(ticket, "/v1/wake/session")
        return Api.epoch.commit(ticket) {
            val s = o.optJSONObject("session")
            session.value = s
            offline.value = false
            if (s != null) markLocalFire(ok = true)
            s
        }
    }

    suspend fun save(c: Context, cfg: JSONObject, ticket: Long = Api.epoch.capture()): JSONObject {
        val o = request(ticket, "/v1/wake/config", "PUT", cfg)
        return Api.epoch.commit(ticket) {
            owner()
            sp.edit().putString("ov", o.toString()).apply()
            overview.value = o
            schedule(c, o, ticket)
            o
        }
    }

    fun phase(s: JSONObject?): String = s?.str("phase")?.ifBlank { null } ?: "idle"

    private fun am(c: Context) = c.getSystemService(AlarmManager::class.java)
    private const val PI = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun firePi(c: Context, aid: String) =
        PendingIntent.getForegroundService(c, 100, intent(c, "fire").putExtra("aid", aid), PI)

    private fun checkPi(c: Context) = PendingIntent.getForegroundService(c, 102, intent(c, "check"), PI)

    fun canExact(c: Context) = am(c).canScheduleExactAlarms()

    fun notificationsReady(c: Context): Boolean {
        val nm = c.getSystemService(NotificationManager::class.java)
        return nm.areNotificationsEnabled() && (nm.getNotificationChannel(CH)?.importance ?: 0) >= NotificationManager.IMPORTANCE_HIGH
    }

    fun canFullScreen(c: Context): Boolean = android.os.Build.VERSION.SDK_INT < 34 ||
        c.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /** Local alarms from the last known server state, so the phone still rings with no network. */
    fun schedule(c: Context, o: JSONObject? = null, ticket: Long = Api.epoch.capture()): Unit = Api.epoch.commit(ticket) {
        val am = am(c)
        if (!enabled || !Prefs.paired) { cancelAlarm(c, 100, "fire"); cancelAlarm(c, 102, "check"); return@commit }
        val state = o ?: overview.value ?: return@commit
        val next = state.optJSONObject("next")
        val at = ((next?.optDouble("ts", 0.0) ?: 0.0) * 1000).toLong()
        // Cached server state can refer to yesterday's alarm after a reboot or settings round-trip.
        if (next != null && at > System.currentTimeMillis() && canExact(c)) {
            val show = PendingIntent.getActivity(c, 101, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PI)
            runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), firePi(c, next.str("alarm_id"))) }
                .onFailure { Log.w("hermes", "wake alarm not set: ${it.message}") }
        } else cancelAlarm(c, 100, "fire")
        scheduleCheck(c, state.optJSONObject("session"), ticket)
    }

    fun scheduleCheck(c: Context, s: JSONObject?, ticket: Long = Api.epoch.capture()): Unit = Api.epoch.commit(ticket) {
        val due = s?.optDouble("checkin_due", 0.0) ?: 0.0
        val at = (due * 1000).toLong() + 20_000
        if (enabled && Prefs.paired && phase(s) == "wait" && due > 0 && at > System.currentTimeMillis() && canExact(c)) {
            runCatching { am(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, checkPi(c)) }
                .onFailure { Log.w("hermes", "wake check-in not set: ${it.message}") }
        } else cancelAlarm(c, 102, "check")
        Unit
    }

    fun onPush(c: Context, action: String) {
        val ticket = Api.epoch.capture()
        when (action) {
            "ring", "checkin" -> WakeService.start(c, "check", ticket)
            "done", "content" -> if (WakeService.running) WakeService.start(c, "check", ticket)
            else -> Hub.scope.launch { try { refresh(c, ticket) } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
        }
    }

    /** Offline fallback puzzle: two-digit arithmetic, enough to need a working brain. */
    fun localPuzzle(): Pair<String, Int> {
        val a = Random.nextInt(14, 49); val b = Random.nextInt(12, 39); val k = Random.nextInt(3, 8)
        return "$a + $b × $k = ?" to (a + b * k)
    }
}

class WakeService : Service() {
    private var player: MediaPlayer? = null
    private var loop: Job? = null
    private var wl: PowerManager.WakeLock? = null
    private var savedVol = -1
    private var checkinSince = 0L
    @Volatile private var pendingFire: String? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = synchronized(Api.epoch) {
        // Reject legacy, already-delivered and previous-pairing intents. Do not let a stale intent
        // stop a newer machine's already-running alarm. Null sticky restarts have no provenance.
        if (!Wake.accepts(intent)) {
            if (!running) finish()
            return@synchronized START_NOT_STICKY
        }
        if (intent?.action == "stop") { finish(); return@synchronized START_NOT_STICKY }
        val ticket = Api.epoch.capture()
        active = this
        try {
            ServiceCompat.startForeground(this, 9, note("Prove you're awake"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: RuntimeException) {
            Log.w("hermes", "wake foreground start rejected", e)
            finish(); return@synchronized START_NOT_STICKY
        }
        running = true
        if (wl == null) wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hermes:wake").apply { acquire(100 * 60_000L) }
        if (intent?.action == "fire") {
            Wake.markLocalFire(ticket = ticket)
            pendingFire = intent.getStringExtra("aid").orEmpty()
            loud()
        }
        if (loop == null) loop = Hub.scope.launch { follow(ticket) }
        START_NOT_STICKY
    }

    private suspend fun follow(ticket: Long) {
        while (true) {
            Api.epoch.commit(ticket) { pendingFire }?.let { aid ->
                try {
                    Wake.request(ticket, "/v1/wake/fired", "POST", JSONObject().put("alarm_id", aid))
                    Api.epoch.commit(ticket) { if (pendingFire == aid) pendingFire = null }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
            currentCoroutineContext().ensureActive()
            val s = if (Api.epoch.commit(ticket) { pendingFire != null }) {
                Api.epoch.commit(ticket) { Wake.offline.value = true }; null
            } else try { Wake.pull(ticket) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { Api.epoch.commit(ticket) { Wake.offline.value = true }; null }
            currentCoroutineContext().ensureActive()
            val keepFollowing = Api.epoch.commit(ticket) {
                val ph = if (Wake.offline.value) (if (Wake.localRinging()) "ring" else Wake.phase(Wake.session.value)) else Wake.phase(s)
                val now = System.currentTimeMillis()
                when (ph) {
                    "ring" -> { checkinSince = 0; if (now < Wake.quietUntil) quiet() else loud() }
                    "checkin" -> {
                        if (checkinSince == 0L) { checkinSince = now; openScreen() }
                        if (now < Wake.quietUntil) quiet() else if (now - checkinSince < 40_000) soft() else loud()
                    }
                    "wait" -> { quiet(); Wake.scheduleCheck(this, s, ticket); finish(); return@commit false }
                    else -> { if (!Wake.offline.value || !Wake.localRinging()) { finish(); return@commit false } }
                }
                true
            }
            if (!keepFollowing) return
            delay(6_000)
        }
    }

    private fun openScreen() {
        // Background alarms use the system's full-screen notification policy, not a blocked activity start.
        if (Hub.foreground) runCatching { startActivity(Intent(this, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        runCatching { getSystemService(NotificationManager::class.java).notify(9,
            note(if (Wake.phase(Wake.session.value) == "checkin") "Check-in: one quick puzzle" else "Prove you're awake")) }
    }

    private fun note(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 9, Intent(this, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, Wake.CH).setSmallIcon(R.drawable.ic_stat).setColor(0xFFFF8A66.toInt())
            .setContentTitle("Wake").setContentText(text).setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setContentIntent(pi)
            .apply { if (Wake.canFullScreen(this@WakeService)) setFullScreenIntent(pi, true) }.build()
    }

    private fun audio() = getSystemService(AudioManager::class.java)

    private fun loud() {
        val am = audio()
        if (savedVol < 0) savedVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
        runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
            .onFailure { Log.w("hermes", "alarm volume restricted by device policy", it) }
        player?.setVolume(1f, 1f)
        if (player == null) {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    setDataSource(this@WakeService, uri); isLooping = true; prepare(); start()
                }
            }.getOrNull()
            openScreen()
        }
        vibrate(true)
    }

    private fun soft() {
        if (player == null) loud()
        player?.setVolume(0.25f, 0.25f)
    }

    private fun quiet() {
        player?.runCatching { stop(); release() }
        player = null
        vibrate(false)
    }

    private fun vibrate(on: Boolean) {
        val v = getSystemService(VibratorManager::class.java).defaultVibrator
        if (on) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()) else v.cancel()
    }

    private fun finish() {
        loop?.cancel(); loop = null
        pendingFire = null; checkinSince = 0
        quiet()
        if (savedVol >= 0) runCatching { audio().setStreamVolume(AudioManager.STREAM_ALARM, savedVol, 0) }
        savedVol = -1
        wl?.runCatching { if (isHeld) release() }; wl = null
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        synchronized(Api.epoch) {
            finish()
            if (active === this) active = null
        }
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
        private var active: WakeService? = null

        internal fun reset(c: Context) {
            // Stop the loop/audio synchronously; stopService alone defers cleanup to onDestroy.
            active?.finish()
            c.stopService(Intent(c, WakeService::class.java))
        }

        fun start(c: Context, action: String, ticket: Long = Api.epoch.capture()) {
            Api.epoch.commit(ticket) {
                if (!Prefs.paired) return@commit
                runCatching { ContextCompat.startForegroundService(c, Wake.intent(c, action)) }
                    .onFailure { Log.w("hermes", "wake service start failed: ${it.message}") }
            }
        }
    }
}

/** Re-arms the local alarm after reboot, app update or a clock/timezone change. */
class WakeBootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        Wake.schedule(c)
        // Android 14+ forbids starting microphone services from boot. Resume only when the user opens Hermes.
        if (Prefs.wakeWord) WakeListen.state.value = "error:Open Hermes to resume wake word listening"
        if (Prefs.paired) {
            // Network I/O can outlive a broadcast's deadline. Let WorkManager refresh cached alarm state.
            androidx.work.WorkManager.getInstance(c).enqueueUniqueWork("wake_rearm", androidx.work.ExistingWorkPolicy.KEEP,
                androidx.work.OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(
                    androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()).build())
        }
    }
}
