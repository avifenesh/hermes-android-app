package dev.avifenesh.hermes

import dev.avifenesh.hermes.PairOffers.Next
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the pairing screen does with a delivered link, including the same link delivered again through onNewIntent
 * while the screen already shows it (an equal Uri: no recomposition of its own).
 */
class PairOffersTest {
    private val link = "hermes://pair?u=https%3A%2F%2Fa.example%3A47936&c=BADCODE123&n=demo-a"

    @Test fun aRedeliveryWhileTheErrorShowsOffersThePairingAgain() {
        // Paired to machine B. A link for machine A arrives, the user confirms, the claim fails, the error shows.
        val o = PairOffers<String>()
        assertEquals(Next.Confirm, o.delivered(link, hasCode = true, paired = true, redeemed = false))
        assertEquals(link, o.pending.value)
        assertEquals(link, o.take())
        assertNull(o.pending.value)
        o.failed("That code is used or expired. Run hermes mobile pair again.")
        // `adb shell am start -d <same link>`: the activity is on top, so the link arrives through onNewIntent.
        assertEquals(Next.Confirm, o.delivered(link, hasCode = true, paired = true, redeemed = false))
        assertEquals("the prompt is back", link, o.pending.value)
        assertNull("the old failure is gone", o.failure.value)
        assertEquals(link, o.take())
    }

    @Test fun aRedeliveryWhenUnpairedRedeemsAgain() {
        val o = PairOffers<String>()
        assertEquals(Next.Redeem, o.delivered(link, hasCode = true, paired = false, redeemed = false))
        o.failed("That code is used or expired. Run hermes mobile pair again.")
        assertEquals(Next.Redeem, o.delivered(link, hasCode = true, paired = false, redeemed = false))
        assertNull(o.pending.value)
    }

    @Test fun aTapTakesThePromptOnce() {
        val o = PairOffers<String>()
        o.delivered(link, hasCode = true, paired = true, redeemed = false)
        assertEquals(link, o.take())
        assertNull(o.take())
    }

    @Test fun redeemedAndCodelessLinksOfferNothingToConfirm() {
        val o = PairOffers<String>()
        assertEquals(Next.AlreadyPaired, o.delivered(link, hasCode = true, paired = true, redeemed = true))
        assertNull(o.pending.value)
        assertEquals(Next.Nothing, o.delivered("hermes://pair", hasCode = false, paired = true, redeemed = false))
        assertNull(o.pending.value)
    }

    @Test fun clearDropsPromptAndFailure() {
        val o = PairOffers<String>()
        o.delivered(link, hasCode = true, paired = true, redeemed = false)
        o.failed("boom")
        o.clear()
        assertNull(o.pending.value)
        assertNull(o.failure.value)
    }
}
