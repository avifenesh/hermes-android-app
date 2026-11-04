package dev.avifenesh.hermes

/** Kinds that carry what a speaker said: consecutive ones from the same role form one run. */
private val RUN_BODY = setOf("text", "image", "file", "voice", "steer", "queued")

/** What the chat lists: no deleted rows, and no transcript echo whose text already sits on its voice message. */
fun chatVisible(all: List<Msg>): List<Msg> {
    val ids = all.mapTo(HashSet()) { it.id }
    return all.filter { m ->
        m.kind != "deleted" && !(m.kind == "status" && m.meta.str("transcript_of").let { it.isNotEmpty() && it in ids })
    }
}

/**
 * True when [msgs][i] starts a speaker run: the previous message that counts (rows for which [quiet]
 * holds, such as tool traces, activity cards and notices, are skipped) came from another role, is a
 * card rather than speech, or is from another day. The first message of an agent run carries the avatar.
 */
fun leadsRun(msgs: List<Msg>, i: Int, quiet: (Msg) -> Boolean, sameDay: (Msg, Msg) -> Boolean): Boolean {
    val m = msgs[i]
    var j = i - 1
    while (j >= 0 && quiet(msgs[j])) j--
    val prev = msgs.getOrNull(j) ?: return true
    return prev.role != m.role || prev.kind !in RUN_BODY || !sameDay(prev, m)
}

/** True when [msgs][i] is the last of its speaker's run (the footer goes there). Rows for which [quiet]
 *  holds between two messages of the same role do not end the run. */
fun endsRun(msgs: List<Msg>, i: Int, quiet: (Msg) -> Boolean): Boolean {
    val m = msgs[i]
    var k = i + 1
    while (k < msgs.size && msgs[k].role == m.role && quiet(msgs[k])) k++
    val next = msgs.getOrNull(k)
    return next == null || next.role != m.role
}
