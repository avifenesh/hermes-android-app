package dev.avifenesh.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** One redemption per pairing code, process-wide, whatever happens to the pairing screen. */
class PairGateTest {
    private val start = PairGate.Verdict.Start

    @Test fun firstLinkStarts() {
        assertEquals(start, PairGate().begin("k", auto = true))
    }

    @Test fun recreatedScreenDoesNotRedeemAgainWhileInFlight() {
        val g = PairGate()
        assertEquals(start, g.begin("k", auto = true))
        // Rotation/theme change: the new composition's LaunchedEffect delivers the same link again.
        assertEquals(PairGate.Verdict.InFlight, g.begin("k", auto = true))
        assertEquals(PairGate.Verdict.InFlight, g.begin("k", auto = false))
    }

    @Test fun anotherCodeWaitsForTheOneInFlight() {
        val g = PairGate()
        assertEquals(start, g.begin("a", auto = false))
        assertEquals(PairGate.Verdict.InFlight, g.begin("b", auto = false))
        g.finish("a", redeemed = false)
        assertEquals(start, g.begin("b", auto = false))
    }

    @Test fun redeemedCodeIsAlreadyPaired() {
        val g = PairGate()
        g.begin("k", auto = true)
        g.finish("k", redeemed = true)
        assertEquals(PairGate.Verdict.Redeemed, g.begin("k", auto = true))
        assertEquals(PairGate.Verdict.Redeemed, g.begin("k", auto = false))
        assertEquals(start, g.begin("other", auto = true))
    }

    @Test fun failedAutoAttemptIsNotRepeatedByRecreationButATapRetries() {
        val g = PairGate()
        g.begin("k", auto = true)
        g.finish("k", redeemed = false)
        assertEquals(PairGate.Verdict.Attempted, g.begin("k", auto = true))
        assertEquals(start, g.begin("k", auto = false))
    }

    @Test fun finishingAnotherCodeDoesNotReleaseTheOneInFlight() {
        val g = PairGate()
        g.begin("a", auto = false)
        g.finish("b", redeemed = true)
        assertEquals(PairGate.Verdict.InFlight, g.begin("a", auto = false))
    }

    @Test fun redeemedHashesSurviveAProcessRestart() {
        val g = PairGate()
        g.begin("k", auto = false)
        g.finish("k", redeemed = true)
        val next = PairGate(g.redeemed())
        assertEquals(PairGate.Verdict.Redeemed, next.begin("k", auto = true))
    }

    @Test fun forgettingAFailedLinkLetsItsReopenRetry() {
        // The screen left (or the link came in again from outside): a genuine re-open is a new automatic attempt.
        val g = PairGate()
        g.begin("k", auto = true)
        g.finish("k", redeemed = false)
        assertEquals(PairGate.Verdict.Attempted, g.begin("k", auto = true))
        g.forget("k")
        assertEquals(start, g.begin("k", auto = true))
    }

    @Test fun forgettingNeverUnredeemsOrReleasesTheCodeInFlight() {
        val g = PairGate()
        g.begin("a", auto = false)
        g.forget("a")
        assertEquals(PairGate.Verdict.InFlight, g.begin("a", auto = true))
        g.finish("a", redeemed = true)
        g.forget("a")
        assertEquals(PairGate.Verdict.Redeemed, g.begin("a", auto = true))
    }

    @Test fun keyIsAHashOfServerAndCodeNotTheCode() {
        val k = PairGate.key("https://box.example:9445/", "Zx81secretcode")
        assertFalse(k.contains("Zx81secretcode"))
        assertEquals(64, k.length)
        assertEquals(k, PairGate.key("https://box.example:9445", "Zx81secretcode"))
        assertNotEquals(k, PairGate.key("https://other.example:9445", "Zx81secretcode"))
        assertNotEquals(k, PairGate.key("https://box.example:9445", "Zx81secretcodf"))
    }
}
