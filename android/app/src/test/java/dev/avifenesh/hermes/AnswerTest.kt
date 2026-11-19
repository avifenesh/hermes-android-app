package dev.avifenesh.hermes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The server hashes JSON.stringify of the answers sorted by id; the phone must sign the same bytes. */
class AnswerTest {
    @Test fun severalAnswersSignTheServersJsonForm() {
        val a = Answer.each(mapOf("checks" to listOf("Unit", "Integration"), "build" to listOf("Debug \"fast\"/x\n")))
        assertEquals("""[["build","Debug \"fast\"/x\n"],["checks",["Unit","Integration"]]]""", a.signed)
        val body = JSONObject().also { a.into(it) }
        assertEquals("Debug \"fast\"/x\n", body.getJSONObject("answers").getString("build"))
        assertEquals(2, body.getJSONObject("answers").getJSONArray("checks").length())
    }

    @Test fun oneAnswerSignsItsTrimmedWords() {
        val a = Answer.one("  Red ")
        assertEquals("Red", a.signed)
        assertEquals("Red", JSONObject().also { a.into(it) }.getString("answer"))
    }
}
