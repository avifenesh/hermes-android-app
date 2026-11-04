package dev.avifenesh.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Play: the dataSync service may be foreground only while a run started from this phone is in progress. */
class RunFollowTest {
    private val grace = 30_000L
    private val cap = 6 * 60 * 60 * 1000L
    private fun follow() = RunFollow(grace, cap)

    @Test fun pairingAloneNeverHoldsTheService() {
        val f = follow()
        f.working(setOf("cron-chat"))
        f.expire(0)
        assertFalse("runs this phone did not start must not keep a connection", f.active())
        assertNull(f.wakeIn(0))
    }

    @Test fun followsAStartedRunUntilItsChatStopsWorking() {
        val f = follow()
        assertTrue(f.start("c1", afterSeq = 10, now = 0, foreground = true))
        assertTrue(f.active())
        f.working(setOf("c1"))
        f.expire(grace + 1)
        assertTrue("a run seen working outlives the start grace", f.active())
        f.working(emptySet())
        assertFalse("run ended or failed: the chat left the working set", f.active())
    }

    @Test fun aRunThatNeverStartsExpiresAfterTheGrace() {
        val f = follow()
        f.start("c1", 10, now = 1_000, foreground = true)
        f.expire(1_000 + grace - 1)
        assertTrue(f.active())
        assertEquals(1L, f.wakeIn(1_000 + grace - 1))
        f.expire(1_000 + grace)
        assertFalse(f.active())
    }

    @Test fun aReplyEndsARunNeverSeenWorking() {
        val f = follow()
        f.start("c1", afterSeq = 10, now = 0, foreground = true)
        f.replied("c1", 10)
        assertTrue("the user's own message or older history is not a reply", f.active())
        f.replied("c2", 99)
        assertTrue(f.active())
        f.replied("c1", 11)
        assertFalse("an instant command answered without a working phase", f.active())
    }

    @Test fun aReplyDuringAWorkingRunWaitsForTheRunToEnd() {
        val f = follow()
        f.start("c1", 10, 0, true)
        f.working(setOf("c1"))
        f.replied("c1", 11)
        assertTrue("streamed partial replies arrive while the run works", f.active())
        f.working(emptySet())
        assertFalse(f.active())
    }

    @Test fun androidFifteenBudgetCapsEveryRunOnEveryApiLevel() {
        val f = follow()
        f.start("c1", 1, now = 5, foreground = true)
        f.working(setOf("c1"))
        f.expire(5 + cap - 1)
        assertTrue(f.active())
        assertEquals(1L, f.wakeIn(5 + cap - 1))
        f.expire(5 + cap)
        assertFalse("a chat the gateway never clears cannot hold the service past the dataSync budget", f.active())
    }

    @Test fun timeoutDropsRunsAndRefusesBackgroundStartsUntilForeground() {
        val f = follow()
        f.start("c1", 1, 0, true)
        f.working(setOf("c1"))
        f.timedOut()
        assertFalse(f.active())
        assertFalse("Android 15 refuses dataSync until the app is foreground again", f.start("c2", 2, 10, foreground = false))
        assertFalse(f.active())
        assertTrue(f.start("c2", 2, 20, foreground = true))
        assertTrue(f.active())
        f.timedOut()
        f.foreground()
        assertTrue("bringing the app to the foreground resets the budget", f.start("c3", 3, 30, foreground = false))
    }

    @Test fun startFailureAndMachineChangesClearEverything() {
        val f = follow()
        f.start("c1", 1, 0, true)
        f.start("c2", 2, 0, true)
        f.clear()
        assertFalse(f.active())
        assertTrue(f.followed().isEmpty())
    }

    @Test fun eachRunEndsIndependently() {
        val f = follow()
        f.start("c1", 1, 0, true)
        f.start("c2", 2, 100, true)
        f.working(setOf("c1", "c2"))
        f.working(setOf("c2"))
        assertTrue(f.active())
        assertEquals(listOf("c2"), f.followed())
        f.working(emptySet())
        assertFalse(f.active())
    }

    @Test fun restartingAFollowedRunKeepsItsStartAndState() {
        val f = follow()
        f.start("c1", 5, now = 0, foreground = true)
        f.working(setOf("c1"))
        // A steer or an approval decision from this phone while the same run works.
        f.start("c1", 9, now = 1_000, foreground = true)
        assertEquals(0L, f.startedAt("c1"))
        f.expire(grace + 5_000)
        assertTrue("still seen working, so the start grace no longer applies", f.active())
        f.replied("c1", 20)
        assertTrue(f.active())
    }

    @Test fun approvalWaitEndsTheRunAndTheDecisionResumesIt() {
        val f = follow()
        f.start("c1", 5, 0, true)
        f.working(setOf("c1"))
        // The gateway pauses typing while it waits for an approval; the server reaps working after 14 s.
        f.working(emptySet())
        assertFalse("no foreground service while the run waits for the user", f.active())
        assertTrue(f.start("c1", afterSeq = 12, now = 60_000, foreground = false))
        f.working(setOf("c1"))
        assertTrue(f.active())
    }

    @Test fun followedListsNewestFirst() {
        val f = follow()
        f.start("old", 1, 0, true)
        f.start("new", 2, 50, true)
        assertEquals(listOf("new", "old"), f.followed())
    }
}
