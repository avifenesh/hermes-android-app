package dev.avifenesh.hermes

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Play: progress of a run the user started, or approved a step of, from this phone. Play allows a foreground service
 * only for as long as its task needs, so this dataSync service runs only while [LiveLifecycle] follows a run, and
 * stops by itself when the run ends, fails, never starts, loses its gateway, or reaches the Android 15 dataSync
 * limit. Pairing never starts it and there is no always-on connection: without push, other notifications arrive
 * when the app opens or at the periodic sync.
 *
 * Every lifecycle decision runs on the main thread (onStartCommand, the follow loop, onTimeout, onDestroy, and
 * the posted [starting]/[started]/[abandon]/[reset] calls), so a start request and a stop can never interleave.
 */
class LiveService : Service(), LiveLifecycle.Instance {
    private var job: Job? = null
    private var shown: List<Any?>? = null
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (life.onStart(this, startId, intent?.action == STOP) && job == null) {
            job = Hub.scope.launch(Dispatchers.Main.immediate) { follow() }
        }
        version.update { it + 1 }
        // The followed runs live in this process. A restarted process has nothing to follow.
        return START_NOT_STICKY
    }

    private suspend fun follow() {
        combine(Hub.working, Hub.msgs, Hub.chats, Hub.online, version) { _, _, _, _, _ -> Unit }.collectLatest {
            while (true) {
                val msgs = Hub.msgs.value
                val wait = life.step(System.currentTimeMillis(), Hub.online.value, Hub.working.value.keys) { chat ->
                    msgs[chat]?.lastOrNull { it.role != "user" && it.kind in REPLY_KINDS }?.seq
                } ?: return@collectLatest
                delay(wait)
            }
        }
    }

    override fun promote() {
        shown = null
        ServiceCompat.startForeground(this, ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun demote() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(ID)
        shown = null
    }

    override fun stopIfLatest(startId: Int): Boolean = stopSelfResult(startId)

    override fun notifyProgress() {
        val chats = life.runs.followed()
        val key = listOf(chats, chats.map { Hub.chats.value[it]?.title }, Hub.online.value, Hub.agent.value, chats.firstOrNull()?.let { Hub.working.value[it] })
        if (key == shown) return
        shown = key
        Notify.post(this, ID, notification(this))
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        life.onTimeout()
        version.update { it + 1 }
    }

    override fun onDestroy() {
        job?.cancel(); job = null
        life.onDestroy(this)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_NAME = "Run progress"
        const val CHANNEL_DESCRIPTION = "Shown while a run works after you send a message or approve a step from this phone"
        private const val ID = 7
        private const val STOP = "stop"
        private const val LIME = 0xFFD4F75C.toInt()
        /** Agent output that answers a send; a run never seen working ends when one arrives. */
        private val REPLY_KINDS = setOf("text", "image", "file", "voice", "approval")
        private val version = MutableStateFlow(0L)
        private val main by lazy { Handler(Looper.getMainLooper()) }

        internal val life = LiveLifecycle(RunFollow(), object : LiveLifecycle.Host {
            override val paired get() = Prefs.paired
            override val foreground get() = Hub.foreground
            override val talkActive get() = TalkService.active
            override fun requestStart() {
                try { ContextCompat.startForegroundService(Hub.app, Intent(Hub.app, LiveService::class.java)) }
                catch (e: RuntimeException) { Log.w("hermes", "run progress unavailable: ${e.message}"); throw e }
            }
            override fun connect() = Hub.connect()
            override fun disconnect() = Hub.disconnect()
        })

        /** Always post, even from the main thread: one FIFO orders every start request against every stop. */
        private fun onMain(block: () -> Unit) { main.post { block(); version.update { it + 1 } } }

        @Suppress("UNUSED_PARAMETER")
        fun sync(c: Context) = onMain { life.request() }

        fun keepConnected(): Boolean = life.keepConnected()

        fun firstPair() = life.firstPair()
        fun afterPair(push: Boolean) = life.afterPair(push)
        @Suppress("UNUSED_PARAMETER")
        fun pushReady(c: Context) = life.pushReady()

        /** The user sends or decides an approval in [chat]: follow now, while the app may still start a foreground
         *  service, before the request returns. [nextTurn]: a send (not a steer) into a working chat queues a turn. */
        @Suppress("UNUSED_PARAMETER")
        internal fun starting(c: Context, chat: String, afterSeq: Long, nextTurn: Boolean): LiveLifecycle.Follow {
            val f = LiveLifecycle.Follow(chat)
            onMain { life.starting(f, afterSeq, System.currentTimeMillis(), nextTurn) }
            return f
        }

        /** The gateway accepted it; [afterSeq] is the accepted message, the reply baseline. */
        @Suppress("UNUSED_PARAMETER")
        internal fun started(c: Context, f: LiveLifecycle.Follow, afterSeq: Long) =
            onMain { life.started(f, afterSeq, System.currentTimeMillis()) }

        /** The request failed or resumed nothing. */
        @Suppress("UNUSED_PARAMETER")
        internal fun abandon(c: Context, f: LiveLifecycle.Follow) = onMain { life.abandon(f) }

        fun foreground() = life.runs.foreground()

        /** Unpair or machine switch: the previous machine's runs are not this one's. The service stops itself. */
        fun reset() = onMain { life.runs.clear() }

        /** Unpair. Never stopService: a pending startForegroundService would then crash the app. */
        @Suppress("UNUSED_PARAMETER")
        fun stop(c: Context) = reset()

        private fun notification(c: Context): Notification {
            val chats = life.runs.followed()
            val chat = chats.firstOrNull()
            val stop = PendingIntent.getService(c, 48, Intent(c, LiveService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
            val open = chat?.let { Notify.openIntent(c, it) } ?: PendingIntent.getActivity(c, 3,
                Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val since = chat?.let { Hub.working.value[it]?.let { s -> (s * 1000).toLong() } ?: life.runs.startedAt(it) }
            val b = NotificationCompat.Builder(c, Notify.CH_LIVE).setSmallIcon(R.drawable.ic_stat).setColor(LIME)
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .setContentTitle(Hub.agent.value + " is working")
                .setContentText(chat?.let { Hub.chats.value[it]?.title } ?: "A run")
                .setProgress(0, 0, true).setContentIntent(open)
                .addAction(0, "Stop updates", stop)
                .setSubText(if (!Hub.online.value) "Reconnecting…" else if (chats.size > 1) "${chats.size} runs" else null)
            if (since != null) b.setShowWhen(true).setUsesChronometer(true).setWhen(since) else b.setShowWhen(false)
            return b.build()
        }
    }
}
