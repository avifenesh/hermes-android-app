package dev.avifenesh.hermes

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Always-on wake word. A microphone foreground service reads 80 ms chunks from AudioRecord and detects the phrase on-device
 * with [WakeWord]. On a hit it opens Talk, which starts listening right away. While Talk owns the mic the detector
 * pauses (SpeechRecognizer and AudioRecord can't share it), and resumes when Talk goes idle or closes.
 */
class WakeListenService : Service() {
    @Volatile private var run = false
    @Volatile private var testPcm: ShortArray? = null
    private var testPos = 0
    private fun pcmOf(wav: ByteArray): ShortArray = WakePcm.wav(wav)
    private var th: Thread? = null
    @Volatile private var activeRecorder: AudioRecord? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { Prefs.wakeWord = false; WakeListen.state.value = "off"; stopSelf(); return START_NOT_STICKY }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            WakeListen.state.value = "error:Microphone permission needed"; stopSelf(); return START_NOT_STICKY
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            WakeListen.state.value = "error:This device has no microphone"; stopSelf(); return START_NOT_STICKY
        }
        if (!WakeListen.notificationsReady(this)) {
            WakeListen.state.value = "error:Allow Wake word notifications in Android settings so Turn off stays available"
            stopSelf(); return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, 11, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: RuntimeException) {
            Log.w(TAG, "foreground start rejected", e)
            WakeListen.state.value = "error:Open Hermes to resume wake word listening"
            stopSelf(); return START_NOT_STICKY
        }
        // Debug builds: feed a 16 kHz WAV through the live detector instead of the mic (emulators have no mic input).
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            intent?.getStringExtra("testwav")?.let { f -> testPcm = runCatching { pcmOf(java.io.File(f).readBytes()) }.getOrNull() }
        if (th == null) { run = true; th = Thread(::loop, "wakeword").apply { isDaemon = true; start() } }
        return START_STICKY
    }

    private fun note() = NotificationCompat.Builder(this, WakeListen.CH).setSmallIcon(R.drawable.ic_stat).setColor(0xFFD4F75C.toInt())
        .setContentTitle("Listening for \u201c${WakeWord.label(Prefs.wakePhrase)}\u201d")
        .setContentText("On-device. Nothing leaves the phone until you say it.")
        .setOngoing(true).setSilent(true).setShowWhen(false)
        .setContentIntent(PendingIntent.getActivity(this, 48, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "Turn off", PendingIntent.getService(this, 49, Intent(this, WakeListenService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE))
        .build()

    @SuppressLint("MissingPermission")
    private fun loop() {
        val ww = try {
            WakeWord.load(assets, Prefs.wakeThreshold)
        } catch (e: Throwable) {
            Log.e(TAG, "model load failed", e); WakeListen.state.value = "error:Wake model failed to load"; stopSelf(); return
        }
        val min = AudioRecord.getMinBufferSize(WakeWord.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val buf = ShortArray(WakeWord.CHUNK)
        var rec: AudioRecord? = null
        var cooldownUntil = 0L // Keep the existing two-second wall-clock cooldown, not chunk counting.
        var streamOrigin = Api.epoch.capture()
        var cutoff = Prefs.wakeThreshold
        var fixtureSource: ShortArray? = null
        try {
            while (run) {
                if (WakeListen.paused.value) {
                    activeRecorder = null
                    rec?.let { runCatching { it.stop() }; it.release() }; rec = null
                    WakeListen.state.value = "paused"; Thread.sleep(250); continue
                }
                if (rec == null) {
                    val recorderOrigin = Api.epoch.capture()
                    rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeWord.RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, maxOf(min, WakeWord.CHUNK * 4))
                    check(rec.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable. Close other recording apps and try again." }
                    activeRecorder = rec
                    if (!run) break
                    rec.startRecording()
                    check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone could not start" }
                    cutoff = Prefs.wakeThreshold
                    ww.reset(cutoff)
                    streamOrigin = recorderOrigin
                    WakeListen.state.value = "listening"
                }
                val origin = Api.epoch.capture()
                val configuredCutoff = Prefs.wakeThreshold
                if (streamOrigin != origin) {
                    // AudioRecord can still have old-identity PCM queued. Reopen, don't just reset TFLM.
                    activeRecorder = null
                    rec?.let { runCatching { it.stop() }; it.release() }; rec = null
                    ww.reset(configuredCutoff)
                    streamOrigin = origin
                    continue
                }
                if (cutoff != configuredCutoff) {
                    cutoff = configuredCutoff
                    ww.reset(cutoff)
                }
                var got = 0
                while (got < buf.size && run) {
                    val n = rec.read(buf, got, buf.size - got)
                    if (!run) break
                    check(n > 0) { "Microphone stopped ($n). Reopen Hermes to try again." }
                    got += n
                }
                if (!run) break
                if (got < buf.size) continue
                val fixture = testPcm
                if (fixture !== fixtureSource) {
                    ww.reset(cutoff)
                    fixtureSource = fixture
                    testPos = 0
                }
                fixture?.let { t ->
                    if (testPos + buf.size <= t.size) {
                        System.arraycopy(t, testPos, buf, 0, buf.size)
                        testPos += buf.size
                    } else {
                        testPcm = null
                        fixtureSource = null
                        testPos = 0
                        ww.reset(cutoff) // Resume the real mic with no fixture state.
                    }
                }
                // Do not feed audio captured across a machine switch or mic handoff into a fresh stream.
                if (origin != Api.epoch.capture() || WakeListen.paused.value) {
                    activeRecorder = null
                    rec?.let { runCatching { it.stop() }; it.release() }; rec = null
                    ww.reset(cutoff)
                    streamOrigin = Api.epoch.capture()
                    continue
                }
                val detected = ww.feed(buf)
                val diagnostic = ww.snapshot()
                WakeListen.activation.value = diagnostic[4].toFloat() / WakeWord.WINDOW
                WakeListen.warmingUp.value = diagnostic[3] < 0
                val now = android.os.SystemClock.elapsedRealtime()
                if (now < cooldownUntil) continue
                if (detected) {
                    cooldownUntil = now + 2_000L
                    Log.i(TAG, "wake word detected engine=microWakeWord-v2")
                    android.os.Handler(mainLooper).post { if (origin == Api.epoch.capture()) hit() }
                }
            }
        } catch (e: Throwable) {
            if (run) { Log.e(TAG, "loop failed", e); WakeListen.state.value = "error:" + (e.message ?: "listening failed") }
        } finally {
            activeRecorder = null
            rec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            runCatching { ww.close() }
            // A dead detector must not leave an apparently healthy foreground service behind.
            stopSelf()
        }
    }

    private fun hit() {
        if (!run || !VoiceLaunch.enabled()) return
        WakeListen.paused.value = true   // hand the mic to Talk
        runCatching { getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)) }
        WakeListen.hits.value = WakeListen.hits.value + 1
        val i = VoiceLaunch.wakeIntent(this)
        if (Hub.foreground) runCatching { startActivity(i) }
        else {
            // Wake word is not a phone call or alarm. Android requires a user tap to open background Talk.
            // Android 15+ creator opt-in, restricted to this immutable, one-shot private activity.
            // The system sends it only on a notification tap; never send it from the background service.
            val options = android.app.ActivityOptions.makeBasic().apply {
                if (android.os.Build.VERSION.SDK_INT >= 35) setPendingIntentCreatorBackgroundActivityStartMode(
                    android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }.toBundle()
            val pi = PendingIntent.getActivity(this, 50, i,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE, options)
            runCatching { getSystemService(NotificationManager::class.java).notify(12, NotificationCompat.Builder(this, WakeListen.CH_HIT)
                .setSmallIcon(R.drawable.ic_stat).setColor(0xFFD4F75C.toInt()).setContentTitle("Wake word heard")
                .setContentText("Tap to talk with " + Hub.agent.value).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true).setContentIntent(pi).build()) }
        }
        // Existing detector mic-pause recovery only; not a deadline for unlocking/tapping.
        // A later hit supersedes the old one-use capability and notification.
        // Resume after an ignored notification or an activity start the system rejected.
        android.os.Handler(mainLooper).postDelayed({ if (run && !WakeListen.talkActive) WakeListen.paused.value = false }, 15_000)
    }

    override fun onDestroy() {
        run = false
        runCatching { activeRecorder?.stop() } // Unblock a blocking AudioRecord.read before releasing on its owner thread.
        th?.interrupt(); th = null
        if (!WakeListen.talkActive) WakeListen.paused.value = false
        getSystemService(NotificationManager::class.java).cancel(12)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (WakeListen.state.value == "listening" || WakeListen.state.value == "paused") WakeListen.state.value = "off"
        super.onDestroy()
    }

    companion object { private const val TAG = "HermesWakeWord" }
}

object WakeListen {
    const val CH = "wakeword"
    const val CH_HIT = "wakeword_hit"
    val state = MutableStateFlow("off")
    // Actual quantized output-window mean (0..255), NOT a Boolean converted to a probability.
    val activation = MutableStateFlow(0f)
    val warmingUp = MutableStateFlow(true)
    val hits = MutableStateFlow(0)
    val paused = MutableStateFlow(false)
    @Volatile var talkActive = false

    fun channels(c: Context) {
        c.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(CH, "Wake word", NotificationManager.IMPORTANCE_MIN).apply { description = "Shown while listening for the wake word"; setShowBadge(false) },
            NotificationChannel(CH_HIT, "Wake word heard", NotificationManager.IMPORTANCE_HIGH).apply { description = "Opens Talk when you say the wake word"; setShowBadge(false) },
        ))
    }

    fun notificationsReady(c: Context): Boolean {
        val nm = c.getSystemService(NotificationManager::class.java)
        return nm.areNotificationsEnabled() && listOf(CH, CH_HIT).all {
            (nm.getNotificationChannel(it)?.importance ?: NotificationManager.IMPORTANCE_NONE) > NotificationManager.IMPORTANCE_NONE
        }
    }

    /** Call from a visible activity; never attempt microphone access from boot. */
    fun sync(c: Context) {
        val i = Intent(c, WakeListenService::class.java)
        if (!Prefs.wakeWord || !Prefs.paired) { VoiceLaunch.gate.invalidate(); VoiceSession.revokeWake(); c.stopService(i); return }
        val error = when {
            !c.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) -> "This device has no microphone"
            ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> "Microphone permission needed. Allow it in Android app settings."
            !notificationsReady(c) -> "Allow Wake word notifications in Android settings so Turn off stays available"
            else -> null
        }
        if (error != null) { state.value = "error:$error"; c.stopService(i); return }
        if (!Hub.foreground) { state.value = "error:Open Hermes to resume wake word listening"; return }
        runCatching { ContextCompat.startForegroundService(c, i) }
            .onFailure { state.value = "error:Android blocked listening. Open Hermes and try again." }
    }

    /** Talk tells the detector when it holds the mic. */
    fun talk(active: Boolean) { talkActive = active; paused.value = active }
}
