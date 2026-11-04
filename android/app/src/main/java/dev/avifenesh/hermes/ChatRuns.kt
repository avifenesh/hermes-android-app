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

// Gateway progress/housekeeping lines (mirrors store.is_notice on the server, so messages stored before
// the server learned to tag them fold the same way).
private val NOTICE_HEAD = Regex("^\\s*(?:\u23f3|\u267b\ufe0f?|\ud83e\udde0|\ud83d\udcbb|\ud83d\udc0d|\ud83d\udcda|\ud83d\udcd6|\ud83d\udc41\ufe0f?|\u270d\ufe0f?|\ud83d\udd00|\ud83c\udf10|\ud83d\udd0d|\ud83d\udcc4|\u23e9|\u26a1|\ud83e\uddf9|\ud83d\udddc\ufe0f?|\ud83d\udd04|\ud83d\udcbe|\ud83d\udcec|\u2705|\u274c)\\s")
private val NOTICE_LINE = Regex("^\\s*(?:\ud83d\udcbb|\ud83d\udc0d|\ud83d\udcda|\ud83d\udcd6|\ud83d\udc41\ufe0f?|\u270d\ufe0f?|\ud83d\udd00|\ud83c\udf10|\ud83d\udd0d|\ud83d\udcc4)\\s")

fun isNoticeMsg(m: Msg): Boolean {
    if (m.kind == "status") return true
    if (m.kind != "text" || m.role == "user" || m.content.length > 2000) return false
    val lines = m.content.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return false
    if (lines.size == 1) return NOTICE_HEAD.containsMatchIn(lines[0])
    val hits = lines.count { NOTICE_LINE.containsMatchIn(it) }
    return NOTICE_LINE.containsMatchIn(lines[0]) && hits >= maxOf(2, (lines.size + 1) / 2)
}

/** Rows inside a speaker run that do not break it: tool traces, activity cards, notices. */
fun quietInRun(m: Msg): Boolean = m.kind == "activity" || m.kind == "trace" || isNoticeMsg(m)

/** Hermes' echo of a voice note's transcript, once the plugin linked it to that note. */
fun isTranscriptEcho(m: Msg): Boolean = m.kind == "status" && m.meta.str("transcript_of").isNotEmpty()

/** Whether [m] may become its chat's inbox preview and last speaker. The echo repeats the user's own words. */
fun previewCandidate(m: Msg): Boolean = m.kind !in setOf("trace", "activity", "deleted") && !isTranscriptEcho(m)

fun plainText(s: String): String = s.replace(Regex("```[a-zA-Z]*\\n?"), "").replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "\$1")
    .replace(Regex("[*_`#>]"), "").replace(Regex("\\n{3,}"), "\n\n").trim()

/** Inbox preview line. A voice note with no text previews as its transcript, else "Voice message". */
fun chatPreview(m: Msg): String = when (m.kind) {
    "approval" -> str(R.string.chat_preview_approval, bidiIsolate(m.meta.str("command")))
    "image" -> str(R.string.chat_preview_photo) + (if (m.content.isNotBlank()) " · " + m.content else "")
    "file" -> str(R.string.chat_preview_file, bidiIsolate(m.meta.str("file_name")))
    "voice" -> str(R.string.chat_preview_voice)
    else -> plainText(m.content).lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: voicePreview(m) ?: ""
}

private fun voicePreview(m: Msg): String? {
    val clips = m.meta.optJSONArray("attachments").objs().filter { it.str("kind") == "voice" || it.str("mime").startsWith("audio/") }
    if (clips.isEmpty()) return null
    return clips.map { it.str("transcript").trim() }.firstOrNull { it.isNotEmpty() }?.lineSequence()?.first() ?: str(R.string.chat_preview_voice)
}

/**
 * Inbox previews after [batch] landed: for each chat it touched, the newest preview candidate in the
 * merged, time-ordered [history], unless that is older than the chat's last update (only a page of
 * old messages is loaded). Choosing from the merged history, not the batch, keeps an edited older
 * message (a voice note getting its transcript) from replacing a newer preview.
 */
fun withPreviews(chats: Map<String, Chat>, history: Map<String, List<Msg>>, batch: List<Msg>): Map<String, Chat> {
    var out = chats
    for (cid in batch.mapTo(LinkedHashSet()) { it.chatId }) {
        val c = out[cid] ?: continue
        val last = history[cid].orEmpty().lastOrNull { previewCandidate(it) } ?: continue
        if (last.ts >= c.updated - 2) out = out + (cid to c.copy(preview = chatPreview(last), lastRole = last.role))
    }
    return out
}
