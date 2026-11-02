package dev.avifenesh.hermes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSessionMessageTest {
    @Test
    fun mapsCanonicalDisplayMessageAndKeepsText() {
        val m = liveMessage(JSONObject()
            .put("id", "41").put("position", 4).put("role", "assistant")
            .put("text", "answer").put("content", "legacy").put("timestamp", 123.0), "s")
        assertEquals("41", m.id)
        assertEquals("answer", m.content)
        assertEquals("text", m.kind)
        assertEquals(4L, m.seq)
    }

    @Test
    fun toolProjectionUsesCollapsedTraceRenderer() {
        val m = liveMessage(JSONObject().put("id", "t").put("role", "tool")
            .put("display_kind", "tool").put("text", "").put("steps", org.json.JSONArray()), "s")
        assertEquals("trace", m.kind)
    }

    @Test
    fun olderPagesPrependWithoutDuplicatesOrMovedRows() {
        val old = listOf(liveMessage(JSONObject().put("id", "3").put("position", 2)
            .put("role", "assistant").put("text", "three"), "s"))
        val page = listOf(
            liveMessage(JSONObject().put("id", "2").put("position", 1).put("role", "user").put("text", "two"), "s"),
            liveMessage(JSONObject().put("id", "1").put("position", 0).put("role", "user").put("text", "one"), "s"))
        val merged = mergeLiveMessages(old, page)
        assertEquals(listOf("1", "2", "3"), merged.map { it.id })
        assertEquals(merged, mergeLiveMessages(merged, page))
    }

    @Test
    fun refreshBurstAcrossTwoPagesKeepsEveryRow() {
        fun message(i: Int) = liveMessage(JSONObject().put("id", i.toString())
            .put("position", i - 1).put("role", "user").put("text", "message $i"), "s")
        val old = (1..5).map(::message)
        val gapPage = (6..45).map(::message)
        val latestPage = (46..85).map(::message)
        val merged = mergeLiveMessages(old, gapPage + latestPage)
        assertEquals((1..85).map(Int::toString), merged.map { it.id })
    }
}
