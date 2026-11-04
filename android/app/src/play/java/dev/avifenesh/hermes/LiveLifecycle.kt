package dev.avifenesh.hermes

/**
 * Play: the run-progress service's lifecycle, on the main thread only. LiveService posts every entry point here
 * (sends and approval decisions from any thread, onStartCommand, the follow loop, onTimeout, onDestroy), so a start
 * request and a stop decision can never interleave. Android calls go through [Host] and [Instance]; JVM tests model
 * the platform's start-id and startForeground rules.
 */
internal class LiveLifecycle(val runs: RunFollow, private val host: Host) {
    /** Process-wide calls. */
    interface Host {
        val paired: Boolean
        val foreground: Boolean
        val talkActive: Boolean
        /** startForegroundService. Throws when Android refuses (background start, exhausted dataSync budget). */
        fun requestStart()
        fun connect()
        fun disconnect()
    }

    /** The running service instance. */
    interface Instance {
        /** startForeground with the dataSync type. Throws when Android refuses. */
        fun promote()
        /** stopForeground(REMOVE) and cancel the progress notification. */
        fun demote()
        /** stopSelfResult: false while a newer start is pending, which then owes startForeground and gets it. */
        fun stopIfLatest(startId: Int): Boolean
        fun notifyProgress()
    }

    /** A follow created before the request returns; [created] says whether this send added it. */
    class Follow(val chat: String) { var created = false }

    private var svc: Instance? = null
    private var lastStartId = -1
    /** The service is foreground and not stopping. New runs then join it through the follow loop, never through
     *  another startForegroundService: on Android 15+ every startForeground with a time-limited type (dataSync)
     *  re-checks background-start eligibility and throws even for a service that is already foreground. */
    private var promoted = false
    /** Between demote and the next promote: nothing may post the progress notification. */
    var ending = false
        private set

    /** The event stream stays open in the background only for a followed run or an active Talk conversation. */
    fun keepConnected(): Boolean = runs.active() || host.talkActive

    /** Pairing, a machine switch and push checks never start a background connection in this edition. */
    fun firstPair() {}
    @Suppress("UNUSED_PARAMETER") fun afterPair(push: Boolean) {}
    fun pushReady() {}

    /** The user acts from this phone (a send, an approval decision): follow before the request returns, while the
     *  app may still start a foreground service. */
    fun starting(follow: Follow, afterSeq: Long, now: Long, nextTurn: Boolean) {
        follow.created = !runs.has(follow.chat)
        if (runs.start(follow.chat, afterSeq, now, host.foreground, nextTurn)) request(follow)
        else follow.created = false
    }

    /** The gateway accepted it: the reply baseline is the accepted message. Whether the send queued a next turn was
     *  decided in [starting], from the chat's state when the user sent; by now the send's own turn may already show
     *  as working, which must not read as a queued one. */
    fun started(follow: Follow, afterSeq: Long, now: Long) {
        if (runs.start(follow.chat, afterSeq, now, host.foreground)) request(follow)
    }

    /** The request failed or resumed nothing: drop only what this action added and nothing confirmed since. */
    fun abandon(follow: Follow) {
        if (follow.created) runs.abandon(follow.chat)
    }

    /** Start the service if something is followed and it is not already foreground. It stops itself through
     *  [end]; never stopService from here. */
    fun request(follow: Follow? = null) {
        if (!host.paired || !runs.active()) return
        if (svc != null && promoted && !ending) return
        try { host.requestStart() } catch (e: RuntimeException) { refused(follow) }
    }

    /** Android refused the service. Drop only the follow that asked; without one (a promotion or [request] from
     *  the app) no service can carry any run. */
    private fun refused(follow: Follow?) {
        if (follow != null) runs.drop(follow.chat) else runs.clear()
        if (!host.foreground && !keepConnected()) host.disconnect()
    }

    /** onStartCommand. True: run the follow loop. */
    fun onStart(instance: Instance, startId: Int, stopAction: Boolean): Boolean {
        svc = instance
        lastStartId = startId
        // "Stop updates" arrives through startService and owes no startForeground.
        if (stopAction) { runs.clear(); end(); return false }
        // startForegroundService requires startForeground even when the service is about to stop.
        try { instance.promote() } catch (e: RuntimeException) {
            // Already foreground: Android 15+ refused only this re-promotion; the service keeps running as it was.
            if (!promoted) { refused(null); end(); return false }
        }
        promoted = true
        ending = false
        if (!host.paired || !runs.active()) { end(); return false }
        host.connect()
        return true
    }

    /**
     * One pass of the follow loop. [online]: the stream is up and [busy] is current; while it is down the last set
     * is stale, so working runs go back on the grace instead. Returns ms until the next pass, or null when stopping.
     */
    fun step(now: Long, online: Boolean, busy: Set<String>, lastReply: (String) -> Long?): Long? {
        if (online) runs.working(busy, now) else runs.offline(now)
        for (chat in runs.followed()) lastReply(chat)?.let { runs.replied(chat, it) }
        runs.expire(now)
        if (!runs.active()) { end(); return null }
        post()
        return runs.wakeIn(now)
    }

    fun post() {
        if (ending || !runs.active()) return
        svc?.notifyProgress()
    }

    /** Stop now unless Android has a newer start pending; that start promotes again and decides for itself. */
    fun end() {
        val s = svc ?: return
        ending = true
        promoted = false
        s.demote()
        s.stopIfLatest(lastStartId)
    }

    /** Android 15: dataSync has run 6 hours in the last 24 in the background. Stop within seconds; start nothing
     *  until the user opens the app, which resets the budget. */
    fun onTimeout() {
        runs.timedOut()
        end()
        if (!host.foreground && !keepConnected()) host.disconnect()
    }

    fun onDestroy(instance: Instance) {
        if (svc === instance) { svc = null; ending = false; promoted = false; lastStartId = -1 }
        if (!host.foreground && !keepConnected()) host.disconnect()
    }
}
