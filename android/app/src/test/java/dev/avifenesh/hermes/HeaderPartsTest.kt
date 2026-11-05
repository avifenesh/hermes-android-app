package dev.avifenesh.hermes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The chat header shortens only the model; the token count and the cost after it stay whole. */
class HeaderPartsTest {
    @Before fun englishStrings() = TestStrings.install()

    private fun iso(s: String) = "\u2068$s\u2069"

    // Counts and amounts go through ICU, which JVM tests do not have; "included" is the amount these tests can format.
    private val included get() = str(R.string.meter_cost_included)

    @Test fun theModelLeadsAndTheAmountFollowsWhole() {
        val sess = JSONObject().put("model", "claude-opus-5-5").put("cost_status", "included")
        val h = headerParts(sess, null)!!
        assertEquals(iso("opus 5.5"), h.lead)
        assertEquals(included, h.tail)
        assertEquals(h.lead + " · " + h.tail, h.toString())
        assertEquals(h.toString(), headerLine(sess, null))
    }

    @Test fun theTurnsModelWinsAndATurnAloneKeepsItsNumbersInTheTail() {
        val sess = JSONObject().put("model", "claude-opus-5-5")
        assertEquals(iso("synthetic-demo"), headerParts(sess, JSONObject().put("model", "synthetic-demo"))!!.lead)
        val turn = JSONObject().put("model", "synthetic-demo").put("cost_status", "included")
        val h = headerParts(null, turn)!!
        assertEquals(iso("synthetic-demo"), h.lead)
        assertEquals(included, h.tail)
    }

    @Test fun nothingToShowIsNoLine() {
        assertEquals(null, headerParts(JSONObject(), null))
        assertEquals(included, headerParts(JSONObject().put("cost_status", "included"), null)!!.toString())
    }
}
