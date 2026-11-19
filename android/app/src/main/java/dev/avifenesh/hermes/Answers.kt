package dev.avifenesh.hermes

import org.json.JSONArray
import org.json.JSONObject

/**
 * The owner's answer to a question card. One question takes one [text]; a card of several takes one entry per
 * question id ([byId]: the labels picked, or the words typed). [signed] is what the approval key signs with the
 * decision: the text itself, or the answers sorted by id as `[["id","label"],["id",["a","b"]]]`, the server's
 * JSON form (no spaces), so both sides hash the same bytes.
 */
class Answer private constructor(private val text: String?, private val byId: Map<String, List<String>>?) {
    companion object {
        fun one(text: String) = Answer(text.trim(), null)
        fun each(byId: Map<String, List<String>>) = Answer(null, byId)
    }

    fun into(body: JSONObject) {
        if (text != null) body.put("answer", text)
        else body.put("answers", JSONObject().also { o ->
            byId!!.forEach { (k, v) -> o.put(k, if (v.size == 1) v[0] else JSONArray(v)) }
        })
    }

    val signed: String get() = text ?: buildString {
        append('[')
        byId!!.keys.sorted().forEachIndexed { i, k ->
            if (i > 0) append(',')
            val v = byId.getValue(k)
            append('[').append(jsonString(k)).append(',')
            if (v.size == 1) append(jsonString(v[0])) else v.joinTo(this, ",", "[", "]") { jsonString(it) }
            append(']')
        }
        append(']')
    }
}

/** A string as JSON.stringify writes it: only the quote, backslash and control characters are escaped. */
internal fun jsonString(s: String): String = buildString {
    append('"')
    for (c in s) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\b' -> append("\\b")
        '\u000C' -> append("\\f")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
    }
    append('"')
}
