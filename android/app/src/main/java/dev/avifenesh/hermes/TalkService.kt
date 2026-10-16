package dev.avifenesh.hermes

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps Talk alive with the screen off: a microphone foreground service (Android requires one to record in the
 * background) plus a partial wake lock while a conversation is active. The Talker itself stays in the Talk screen;
 * the activity is kept alive under the lock screen (showWhenLocked) so recognition and TTS keep running.
 */
class TalkService : Service() {
    private var wl: PowerManager.WakeLock? = null
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        val open = PendingIntent.getActivity(this, 46, Intent(this, MainActivity::class.java).setAction("dev.avifenesh.hermes.talk").putExtra("go", "talk")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 47, Intent(this, TalkService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, Notify.CH_LIVE).setSmallIcon(R.drawable.ic_stat).setColor(0xFFD4F75C.toInt())
            .setContentTitle("Talking with " + Hub.agent.value).setContentText(intent?.getStringExtra("state") ?: "Listening")
            .setOngoing(true).setSilent(true).setContentIntent(open).addAction(0, "End", stop)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build()
        // Android 14+ checks while-in-use microphone permission again inside the service.
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Ui.say("Microphone permission is off. Allow it in Android app settings to use Talk.")
            stopSelf(); return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, 8, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: RuntimeException) {
            android.util.Log.w("hermes", "Talk foreground start rejected", e)
            Ui.say("Android couldn't keep Talk active. Open Talk again while Hermes is on screen.")
            stopSelf(); return START_NOT_STICKY
        }
        if (wl == null) wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hermes:talk").apply { acquire(30 * 60 * 1000L) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wl?.release() }; wl = null
        TalkBus.ended.tryEmit(Unit)
        super.onDestroy()
    }

    companion object {
        fun start(c: Context, state: String) = runCatching {
            ContextCompat.startForegroundService(c, Intent(c, TalkService::class.java).putExtra("state", state))
        }.onFailure {
            android.util.Log.w("hermes", "Talk service start rejected", it)
            TalkBus.ended.tryEmit(Unit)
            Ui.say("Android couldn't start Talk in the background. Open Talk and try again.")
        }
        fun stop(c: Context) { c.stopService(Intent(c, TalkService::class.java)) }
    }
}

object TalkBus { val ended = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
