package dev.avifenesh.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Cancel and the pairing commit are decided once: a cancelled pairing never switches the machine. */
class PairAttemptTest {
    @Test fun cancelBeforeTheCommitDropsTheResult() {
        val a = PairAttempt("k")
        assertTrue(a.cancel())
        assertFalse("the late response must not commit credentials", a.claimCommit())
        assertTrue(a.isCancelled)
    }

    @Test fun cancelAfterTheCommitIsTooLate() {
        val a = PairAttempt("k")
        assertTrue(a.claimCommit())
        assertFalse(a.cancel())
        assertFalse("a committed pairing finishes; its error/success paths stay live", a.isCancelled)
    }

    @Test fun exactlyOneSideWinsARace() {
        repeat(400) {
            val a = PairAttempt("k")
            val go = CountDownLatch(1)
            val committed = AtomicBoolean()
            val cancelled = AtomicBoolean()
            val t1 = thread { go.await(); committed.set(a.claimCommit()) }
            val t2 = thread { go.await(); cancelled.set(a.cancel()) }
            go.countDown(); t1.join(); t2.join()
            assertEquals("run $it", true, committed.get() xor cancelled.get())
        }
    }
}
