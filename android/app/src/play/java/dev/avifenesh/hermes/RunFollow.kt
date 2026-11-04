package dev.avifenesh.hermes

/**
 * Play: the runs started from this phone that the live connection still follows. Pure state; LiveService feeds it.
 *
 * Play allows a foreground service only while it completes a task the user perceives. The dataSync service is
 * foreground exactly while [active]: from a send (or an approval decision that resumes the run) until the chat
 * leaves the gateway's working set, a reply shows the run never needed to work, the start grace passes without a
 * working signal, or the Android 15 dataSync budget runs out.
 */
internal class RunFollow(private val graceMs: Long = START_GRACE_MS, private val capMs: Long = RUN_CAP_MS) {
    private class Run(val startedAt: Long, val afterSeq: Long, val seen: Boolean)

    private val runs = LinkedHashMap<String, Run>()
    /** Android ended or refused dataSync time. Android 15 resets the budget when the app comes to the foreground. */
    private var exhausted = false

    /** The user started (or resumed) a run in [chat] from this phone. False when Android has no dataSync time left. */
    @Synchronized fun start(chat: String, afterSeq: Long, now: Long, foreground: Boolean): Boolean {
        if (foreground) exhausted = false
        if (exhausted) return false
        val old = runs.remove(chat)
        // Re-adding moves the chat to the end, so followed() lists the newest start first.
        runs[chat] = if (old == null) Run(now, afterSeq, false) else Run(old.startedAt, maxOf(old.afterSeq, afterSeq), old.seen)
        return true
    }

    /** Chats the gateway reports as working. A followed run ends (finished, failed or stopped) when its chat leaves. */
    @Synchronized fun working(busy: Set<String>) {
        for (chat in runs.keys.toList()) {
            val run = runs.getValue(chat)
            if (chat in busy) { if (!run.seen) runs[chat] = Run(run.startedAt, run.afterSeq, true) }
            else if (run.seen) runs.remove(chat)
        }
    }

    /** Newest agent message in [chat]. Ends a run never seen working: an instant command or a run that ended unseen. */
    @Synchronized fun replied(chat: String, seq: Long) {
        val run = runs[chat] ?: return
        if (!run.seen && seq > run.afterSeq) runs.remove(chat)
    }

    @Synchronized fun expire(now: Long) {
        runs.entries.removeAll { (_, run) -> now >= deadline(run) }
    }

    /** Android 15 called onTimeout, or refused to start the service: stop following until the app is foreground. */
    @Synchronized fun timedOut() { runs.clear(); exhausted = true }

    @Synchronized fun foreground() { exhausted = false }

    /** Unpair, machine switch, or a service start Android refused for another reason. */
    @Synchronized fun clear() { runs.clear() }

    @Synchronized fun active(): Boolean = runs.isNotEmpty()

    /** Milliseconds until the next run expires by time, or null with nothing to follow. */
    @Synchronized fun wakeIn(now: Long): Long? = runs.values.minOfOrNull { deadline(it) }?.let { maxOf(0L, it - now) }

    /** Followed chats, newest start first. */
    @Synchronized fun followed(): List<String> = runs.keys.reversed()

    @Synchronized fun startedAt(chat: String): Long? = runs[chat]?.startedAt

    private fun deadline(run: Run): Long =
        if (run.seen) run.startedAt + capMs else minOf(run.startedAt + graceMs, run.startedAt + capMs)
}

/**
 * How long a run may take to show as working. The gateway starts its typing refresh as the message's background task
 * begins, and the plugin reports the chat as working on the first refresh, so a real run shows within seconds of the
 * send. 30 s also covers the event stream's first reconnect attempts (1, 2, 4 and 8 s backoff) on a flaky network.
 */
internal const val START_GRACE_MS = 30_000L

/** Android 15 allows dataSync foreground services 6 hours in 24. Apply the same bound to each run on every API level. */
internal const val RUN_CAP_MS = 6 * 60 * 60 * 1000L
