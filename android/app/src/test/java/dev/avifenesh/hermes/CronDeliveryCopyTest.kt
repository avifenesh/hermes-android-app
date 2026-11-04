package dev.avifenesh.hermes

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The cron delivery row counts three numbers with one plural, so its copy must only agree with the total. */
class CronDeliveryCopyTest {
    @Before fun englishStrings() = TestStrings.install()

    @Test fun oneJobElsewhereReadsRight() {
        // 1 of 2 jobs to the app, 1 elsewhere: the old copy said "1 of 2 jobs deliver ...; 1 still go ...".
        assertEquals("To the app: 1 of 2 jobs; Telegram or elsewhere: 1",
            plural(R.plurals.cron_delivery_some_elsewhere, 2, 1, 2, 1))
        assertEquals("To the app: 0 of 1 job; Telegram or elsewhere: 1",
            plural(R.plurals.cron_delivery_some_elsewhere, 1, 0, 1, 1))
    }
}
