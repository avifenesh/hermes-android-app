package dev.avifenesh.hermes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only a decision that resumed a waiting gateway request counts as a run the user moved forward from this phone. */
class ApprovalResumeTest {
    @Test fun aDecisionThatResumedARequestFollowsIt() {
        assertTrue(approvalResumesRun(resolved = 1, state = "approved"))
        assertTrue("a denial lets the agent react", approvalResumesRun(resolved = 1, state = "denied"))
    }

    @Test fun nothingResumedMeansNoFollow() {
        assertFalse("already handled elsewhere", approvalResumesRun(resolved = 0, state = "approved"))
        assertFalse("the gateway no longer waits", approvalResumesRun(resolved = 0, state = "expired"))
        assertFalse(approvalResumesRun(resolved = 1, state = "expired"))
        assertFalse("pending means the decision did not land", approvalResumesRun(resolved = null, state = "pending"))
        assertFalse(approvalResumesRun(resolved = null, state = "expired"))
    }

    @Test fun anOlderGatewayWithoutACountFollowsDecidedStates() {
        assertTrue(approvalResumesRun(resolved = null, state = "approved"))
        assertTrue(approvalResumesRun(resolved = null, state = "denied"))
    }
}
