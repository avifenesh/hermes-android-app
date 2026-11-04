package dev.avifenesh.hermes

/**
 * Play: the runs started from this phone that the live connection still follows. Pure state; LiveLifecycle feeds it.
 *
 * Play allows a foreground service only while it completes a task the user perceives. The dataSync service is
 * foreground exactly while [active]: from a send (or an approval decision that resumes the run) until the chat
 * leaves the gateway's working set, a reply shows the run never needed to work, a grace passes without a working
 * signal (after the send, after the stream drops, or between a turn and the turn a send queued), or the Android 15
 * dataSync budget runs out.
 */
internal class RunFollow(private val graceMs: Long = START_GRACE_MS, private val capMs: Long = RUN_CAP_MS) {
    private enum class State {
        /** Sent, never seen working yet. A reply ends it; so does the grace. */
        PENDING,
        /** The gateway reports the chat as working. Ends when the chat leaves the working set. */
        WORKING,
        /** The turn ended but a send queued another one. Ends on the grace unless the next turn starts. */
        NEXT,
        /** The stream dropped while working. The reconnect's working set re-confirms it within the grace; if the chat
         *  is gone from it, the run ended (or, with a queued turn, waits for that turn as NEXT). */
        UNCONFIRMED,
    }

    private data class Run(val startedAt: Long, val afterSeq: Long, val state: State, val graceFrom: Long, val next: Boolean)

    private val runs = LinkedHashMap<String, Run>()
    /** Android ended or refused dataSync time. Android 15 resets the budget when the app comes to the foreground. */
    private var exhausted = false

    /**
     * The user started (or resumed) a run in [chat] from this phone. [nextTurn]: a send into a chat that is already
     * working queues or interrupts into a new turn, so the follow survives the current turn's end; a steer does not.
     * False when Android has no dataSync time left.
     */
    @Synchronized fun start(chat: String, afterSeq: Long, now: Long, foreground: Boolean, nextTurn: Boolean = false, busy: Boolean = false): Boolean {
        if (foreground) exhausted = false
        if (exhausted) return false
        val old = runs.remove(chat)
        // The send queues a turn when the chat is busy as the user sends: this phone's follow is working, unconfirmed
        // or already between turns, or the gateway lists the chat busy with a run this phone does not follow (cron,
        // another device). Decided at send time, it cannot see the send's own turn.
        val queues = nextTurn && (busy || old?.state == State.WORKING || old?.state == State.UNCONFIRMED || old?.state == State.NEXT)
        // Re-adding moves the chat to the end, so followed() lists the newest start first.
        runs[chat] = if (old == null) Run(now, afterSeq, State.PENDING, now, queues)
            else old.copy(afterSeq = maxOf(old.afterSeq, afterSeq), next = old.next || queues)
        return true
    }

    @Synchronized fun has(chat: String): Boolean = chat in runs

    /** A send that failed before the gateway accepted it: drop the follow it created, unless the run since showed up. */
    @Synchronized fun abandon(chat: String) {
        if (runs[chat]?.state == State.PENDING) runs.remove(chat)
    }

    /** Android refused the service this follow asked for: nothing can carry it. */
    @Synchronized fun drop(chat: String) { runs.remove(chat) }

    /** A fresh working set from the gateway (the stream is up). */
    @Synchronized fun working(busy: Set<String>, now: Long) {
        for (chat in runs.keys.toList()) {
            val run = runs.getValue(chat)
            when {
                chat in busy -> if (run.state != State.WORKING) runs[chat] = run.copy(state = State.WORKING)
                // The turn ended (seen live, or while the stream was down): wait for the queued one on a fresh grace.
                (run.state == State.WORKING || run.state == State.UNCONFIRMED) && run.next ->
                    runs[chat] = run.copy(state = State.NEXT, graceFrom = now, next = false)
                run.state == State.WORKING || run.state == State.UNCONFIRMED -> runs.remove(chat)
            }
        }
    }

    /** The event stream is down: the last working set is stale. Working runs must be re-confirmed within the grace.
     *  A run between turns (NEXT) is already on its grace from the turn's end and stays as it is: the reconnect's
     *  first pass, before the hello brings a fresh set, would otherwise end it. */
    @Synchronized fun offline(now: Long) {
        for ((chat, run) in runs.entries.toList()) {
            if (run.state == State.WORKING) runs[chat] = run.copy(state = State.UNCONFIRMED, graceFrom = now)
        }
    }

    /** Newest agent message in [chat]. Ends a run never seen working: an instant command or a run that ended unseen. */
    @Synchronized fun replied(chat: String, seq: Long) {
        val run = runs[chat] ?: return
        if (run.state == State.PENDING && seq > run.afterSeq) runs.remove(chat)
    }

    @Synchronized fun expire(now: Long) {
        runs.entries.removeAll { (_, run) -> now >= deadline(run) }
    }

    /** Android 15 called onTimeout: stop following until the app is in the foreground again. */
    @Synchronized fun timedOut() { runs.clear(); exhausted = true }

    @Synchronized fun foreground() { exhausted = false }

    /** Unpair, machine switch, Stop updates, or a service start Android refused. */
    @Synchronized fun clear() { runs.clear() }

    @Synchronized fun active(): Boolean = runs.isNotEmpty()

    /** Milliseconds until the next run expires by time, or null with nothing to follow. */
    @Synchronized fun wakeIn(now: Long): Long? = runs.values.minOfOrNull { deadline(it) }?.let { maxOf(0L, it - now) }

    /** Followed chats, newest start first. */
    @Synchronized fun followed(): List<String> = runs.keys.reversed()

    @Synchronized fun startedAt(chat: String): Long? = runs[chat]?.startedAt

    private fun deadline(run: Run): Long =
        if (run.state == State.WORKING) run.startedAt + capMs else minOf(run.graceFrom + graceMs, run.startedAt + capMs)
}

/**
 * How long a run may go without a working signal: after the send, after the stream drops, and between a turn and
 * the turn a send queued. The gateway starts its typing refresh as a message's background task begins, and the
 * plugin reports the chat as working on the first refresh, so a real run shows within seconds. 30 s also covers
 * the event stream's first reconnect attempts (1, 2, 4 and 8 s backoff) on a flaky network.
 */
internal const val START_GRACE_MS = 30_000L

/** Android 15 allows dataSync foreground services 6 hours in 24. Apply the same bound to each run on every API level. */
internal const val RUN_CAP_MS = 6 * 60 * 60 * 1000L
