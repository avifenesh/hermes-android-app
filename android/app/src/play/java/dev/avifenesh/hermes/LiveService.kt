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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Play: progress of a run the user started from this phone. Play allows a foreground service only for as long as
 * its task needs, so this dataSync service runs only while [runs] follows a run, and stops by itself when the run
 * ends, fails, never starts, or reaches the Android 15 dataSync limit. Pairing never starts it and there is no
 * always-on connection: without push, other notifications arrive when the app opens or at the periodic sync.
 */
class LiveService : Service() {
    private var job: Job? = null
    private var shown: List<Any?>? = null
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // "Stop updates" arrives through startService: the run goes on at the gateway, the phone stops following it.
        if (intent?.action == STOP) { reset(); end(); return START_NOT_STICKY }
        // startForegroundService requires startForeground even when the service is about to stop.
        try {
            ServiceCompat.startForeground(this, ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: RuntimeException) {
            // Background-start restrictions or an exhausted Android 15 dataSync budget.
            refused(e)
            stopSelf(); return START_NOT_STICKY
        }
        if (!Prefs.paired || !runs.active()) { end(); return START_NOT_STICKY }
        Hub.connect()
        if (job == null) job = Hub.scope.launch { follow() }
        // The followed runs live in this process. A restarted process has nothing to follow.
        return START_NOT_STICKY
    }

    private suspend fun follow() {
        combine(Hub.working, Hub.msgs, Hub.chats, Hub.online, version) { _, _, _, _, _ -> Unit }.collectLatest {
            while (true) {
                val now = System.currentTimeMillis()
                runs.working(Hub.working.value.keys)
                val msgs = Hub.msgs.value
                for (chat in runs.followed()) {
                    msgs[chat]?.lastOrNull { it.role != "user" && it.kind in REPLY_KINDS }?.let { runs.replied(chat, it.seq) }
                }
                runs.expire(now)
                if (!runs.active()) {
                    // Decide on the main thread, where onStartCommand runs: a send that raced this check keeps it.
                    ContextCompat.getMainExecutor(this@LiveService).execute { if (!runs.active()) end() }
                    return@collectLatest
                }
                post()
                delay(runs.wakeIn(now) ?: return@collectLatest)
            }
        }
    }

    private fun post() {
        val chats = runs.followed()
        val key = listOf(chats, chats.map { Hub.chats.value[it]?.title }, Hub.online.value, Hub.agent.value, chats.firstOrNull()?.let { Hub.working.value[it] })
        if (key == shown) return
        shown = key
        Notify.post(this, ID, notification(this))
    }

    /** Main thread only. */
    private fun end() {
        job?.cancel(); job = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15: dataSync has run 6 hours in the last 24 in the background. Stop within seconds and start no
        // new dataSync service until the user opens the app, which resets the budget.
        runs.timedOut(); version.update { it + 1 }
        end()
        if (!Hub.foreground && !keepConnected()) Hub.disconnect()
    }

    override fun onDestroy() {
        job?.cancel(); job = null
        if (!Hub.foreground && !keepConnected()) Hub.disconnect()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_NAME = "Run progress"
        const val CHANNEL_DESCRIPTION = "Shown while a run you started from this phone is working"
        private const val ID = 7
        private const val STOP = "stop"
        private const val LIME = 0xFFD4F75C.toInt()
        /** Agent output that answers a send; a run never seen working ends when one arrives. */
        private val REPLY_KINDS = setOf("text", "image", "file", "voice", "approval")
        internal val runs = RunFollow()
        private val version = MutableStateFlow(0L)

        fun sync(c: Context) {
            val i = Intent(c, LiveService::class.java)
            if (!Prefs.paired || !runs.active()) { c.stopService(i); return }
            try { ContextCompat.startForegroundService(c, i) } catch (e: RuntimeException) { refused(e) }
        }

        /** Android refused the service: a background start without an exemption, or an exhausted Android 15
         *  dataSync budget. Stop following; a later start from the app or a notification action tries again. */
        private fun refused(e: RuntimeException) {
            Log.w("hermes", "run progress unavailable: ${e.message}")
            runs.clear()
            version.update { it + 1 }
            if (!Hub.foreground && !keepConnected()) Hub.disconnect()
        }

        /** The event stream stays open in the background only for a followed run or an active Talk conversation. */
        fun keepConnected(): Boolean = runs.active() || TalkService.active

        /** Pairing does not start a background connection in this edition. */
        fun firstPair() {}
        @Suppress("UNUSED_PARAMETER")
        fun afterPair(push: Boolean) {}
        @Suppress("UNUSED_PARAMETER")
        fun pushReady(c: Context) {}

        /** The user sent from this phone, or decided an approval that resumes the run in [chat]. */
        fun started(c: Context, chat: String, afterSeq: Long) {
            if (!runs.start(chat, afterSeq, System.currentTimeMillis(), Hub.foreground)) return
            version.update { it + 1 }
            sync(c)
        }

        fun foreground() = runs.foreground()

        /** Unpair or machine switch: the previous machine's runs are not this one's. */
        fun reset() { runs.clear(); version.update { it + 1 } }

        private fun notification(c: Context): Notification {
            val chats = runs.followed()
            val chat = chats.firstOrNull()
            val stop = PendingIntent.getService(c, 48, Intent(c, LiveService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
            val open = chat?.let { Notify.openIntent(c, it) } ?: PendingIntent.getActivity(c, 3,
                Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val since = chat?.let { Hub.working.value[it]?.let { s -> (s * 1000).toLong() } ?: runs.startedAt(it) }
            val b = NotificationCompat.Builder(c, Notify.CH_LIVE).setSmallIcon(R.drawable.ic_stat).setColor(LIME)
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .setContentTitle(Hub.agent.value + " is working")
                .setContentText(chat?.let { Hub.chats.value[it]?.title } ?: "A run you started")
                .setProgress(0, 0, true).setContentIntent(open)
                .addAction(0, "Stop updates", stop)
                .setSubText(if (!Hub.online.value) "Reconnecting…" else if (chats.size > 1) "${chats.size} runs" else null)
            if (since != null) b.setShowWhen(true).setUsesChronometer(true).setWhen(since) else b.setShowWhen(false)
            return b.build()
        }
    }
}
