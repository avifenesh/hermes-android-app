package dev.avifenesh.hermes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** "Restart to apply" is the active machine's server state; only an old server falls back to the app's own flag. */
class RestartBannerTest {
    @Before fun englishStrings() = TestStrings.install()

    private val a = "https://a.example:9445"
    private val b = "https://b.example:9445"

    private fun me(needed: Boolean, pending: Boolean = false, boot: String = "boot-1") =
        JSONObject().put("name", "box").put("restart", JSONObject().put("boot", boot).put("needed", needed).put("pending", pending))

    private val oldServer = JSONObject().put("name", "box")

    @Test fun serverNeededShowsTheBanner() {
        assertTrue(restartBanner(me(needed = true), a, emptySet()))
    }

    @Test fun serverClearedHidesItEvenAfterThisAppFlaggedIt() {
        // Restarted from the CLI, another phone or a crash: the server is the authority.
        assertFalse(restartBanner(me(needed = false), a, setOf(a)))
    }

    @Test fun aRestartUnderWayHidesIt() {
        assertFalse(restartBanner(me(needed = true, pending = true), a, setOf(a)))
    }

    @Test fun oldServerKeepsTheAppFlagPerMachine() {
        assertTrue(restartBanner(oldServer, a, setOf(a)))
        assertFalse("a flag on machine A must not show on machine B", restartBanner(oldServer, b, setOf(a)))
        assertFalse(restartBanner(oldServer, a, emptySet()))
    }

    @Test fun beforeMetadataLoadsOrUnpairedNothingLeaks() {
        assertFalse(restartBanner(JSONObject(), b, setOf(a)))
        assertFalse(restartBanner(JSONObject(), null, setOf(a)))
    }

    @Test fun nothingShowsBeforeThisMachinesMetadataLoads() {
        // Right after a switch or cold start the metadata is empty: the app's own flag must not flash a banner a
        // current server is about to contradict.
        assertFalse(restartBanner(JSONObject(), a, setOf(a)))
    }

    @Test fun aHelloFromANewBootRereadsMetadata() {
        assertTrue(bootChanged(me(needed = true, boot = "boot-1"), "boot-2"))
        assertFalse(bootChanged(me(needed = true, boot = "boot-1"), "boot-1"))
        assertFalse("first load belongs to the normal /v1/me fetch", bootChanged(JSONObject(), "boot-2"))
        assertFalse("old servers send no boot", bootChanged(me(needed = true), ""))
    }

    @Test fun restartToastSaysWhatTheServerDid() {
        assertEquals("Restarting Hermes…", restartToast(JSONObject().put("ok", true).put("state", "restarting")))
        assertEquals("Hermes restarts after 2 running tasks finish",
            restartToast(JSONObject().put("ok", true).put("state", "draining").put("active", 2)))
        assertEquals("Hermes restarts after 1 running task finishes",
            restartToast(JSONObject().put("ok", true).put("state", "draining").put("active", 1)))
        assertEquals("A restart is already under way", restartToast(JSONObject().put("ok", true).put("state", "in_progress")))
        assertEquals("Restarting Hermes…", restartToast(JSONObject().put("ok", true)))  // old server
    }
}
