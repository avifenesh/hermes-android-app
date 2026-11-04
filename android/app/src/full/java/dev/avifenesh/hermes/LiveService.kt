package dev.avifenesh.hermes

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Fallback when push isn't configured: keeps the event stream open; doubles as the live-run notification. */
class LiveService : Service() {
    private var job: Job? = null
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(this, 7, notification(this, Hub.working.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: RuntimeException) {
            // Permission, background-start restrictions, or the Android 15 dataSync budget can reject promotion.
            Log.w("hermes", "live connection unavailable: ${e.message}")
            stopSelf(); return START_NOT_STICKY
        }
        if (!Prefs.paired || !(Prefs.live || Prefs.phoneArmed)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        Hub.connect()
        if (job == null) job = Hub.scope.launch {
            combine(Hub.working, Hub.online, Hub.chats) { w, on, ch -> Triple(w.toString(), on, w.keys.map { ch[it]?.title }) }
                .distinctUntilChanged()
                .collect { Notify.post(this@LiveService, 7, notification(this@LiveService, Hub.working.value)) }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 limits dataSync foreground work to six hours in the background.
        // WorkManager remains scheduled; do not restart a service whose budget is exhausted.
        job?.cancel(); job = null
        if (!Hub.foreground) Hub.disconnect()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel(); job = null
        if (!Hub.foreground) Hub.disconnect()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_NAME = "Live runs & connection"
        const val CHANNEL_DESCRIPTION = "Run progress and the background connection"

        fun sync(c: Context) {
            val i = Intent(c, LiveService::class.java)
            if (Prefs.paired && (Prefs.live || Prefs.phoneArmed)) runCatching { ContextCompat.startForegroundService(c, i) } else c.stopService(i)
        }

        /** The event stream stays open in the background while the user keeps the live connection or arms phone control. */
        fun keepConnected(): Boolean = Prefs.live || Prefs.phoneArmed

        /** A first pairing starts live until push registration proves itself. */
        fun firstPair() { Prefs.live = true }

        /** A machine switch: live exactly when that machine has no working push. */
        fun afterPair(push: Boolean) { Prefs.live = !push }

        /** Push registered: the live fallback is no longer needed. */
        fun pushReady(c: Context) { if (Prefs.live) { Prefs.live = false; sync(c) } }

        /** Full follows every run through its own live connection; nothing per run to track. */
        class Follow internal constructor()

        @Suppress("UNUSED_PARAMETER")
        fun starting(c: Context, chat: String, afterSeq: Long, nextTurn: Boolean) = Follow()

        @Suppress("UNUSED_PARAMETER")
        fun started(c: Context, f: Follow, afterSeq: Long, nextTurn: Boolean) {}

        @Suppress("UNUSED_PARAMETER")
        fun abandon(c: Context, f: Follow) {}

        /** Unpair: the sideload service has no run to wind down. */
        fun stop(c: Context) { c.stopService(Intent(c, LiveService::class.java)) }

        fun foreground() {}

        fun reset() {}

        private const val LIME = 0xFFD4F75C.toInt()
        private const val PI = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        private fun notification(c: Context, working: Map<String, Double>): Notification {
            val b = NotificationCompat.Builder(c, Notify.CH_LIVE).setSmallIcon(R.drawable.ic_stat).setColor(LIME)
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
                .setContentIntent(PendingIntent.getActivity(c, 3, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PI))
            if (working.isEmpty()) {
                b.setContentTitle("Hermes").setContentText(if (Hub.online.value) "Connected" else "Reconnecting…")
            } else {
                val (cid, since) = working.entries.maxBy { it.value }
                b.setContentTitle(Hub.agent.value + " is working").setContentText(Hub.chats.value[cid]?.title ?: "A run")
                    .setProgress(0, 0, true).setShowWhen(true).setUsesChronometer(true).setWhen((since * 1000).toLong())
                    .setContentIntent(Notify.openIntent(c, cid))
                if (working.size > 1) b.setSubText("${working.size} runs")
            }
            return b.build()
        }
    }
}
