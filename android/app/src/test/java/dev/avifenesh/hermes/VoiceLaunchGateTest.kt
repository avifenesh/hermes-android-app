package dev.avifenesh.hermes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLaunchGateTest {
    @Test fun externalExtrasCannotSupplyAuthority() {
        val gate = VoiceLaunchGate()
        for (external in listOf(null, "true", "listen", "talk", "dev.avifenesh.hermes.talk")) {
            assertFalse(gate.consume(external, 1))
        }
    }

    @Test fun trustedHandoffIsOneUseAndNotTimeLimited() {
        val gate = VoiceLaunchGate()
        val token = gate.issue(1)
        // No elapsed-time input: waiting at keyguard does not arbitrarily expire consent.
        assertTrue(gate.consume(token, 1))
        assertFalse(gate.consume(token, 1))
    }

    @Test fun externalEntryRevokesOutstandingHandoff() {
        val gate = VoiceLaunchGate()
        val token = gate.issue(1)
        val generation = gate.generation
        assertFalse(gate.consume(null, 1))
        assertNotEquals(generation, gate.generation)
        assertFalse(gate.consume(token, 1))
    }

    @Test fun supersedingAndInvalidReplayAreFailClosed() {
        val gate = VoiceLaunchGate()
        val first = gate.issue(1)
        val second = gate.issue(1)
        assertNotEquals(first, second)
        assertFalse(gate.consume(first, 1))
        assertFalse(gate.consume(second, 1)) // An invalid entry revoked the newer token too.
    }

    @Test fun recreationAndCancellationCannotReplay() {
        val gate = VoiceLaunchGate()
        val token = gate.issue(1)
        gate.invalidate()
        assertFalse(gate.consume(token, 1))
        assertFalse(VoiceLaunchGate().consume(token, 1))
    }

    @Test fun everyHandoffIsBoundToOriginatingMachineEpoch() {
        val gate = VoiceLaunchGate()
        val detector = gate.issue(7)
        assertFalse(gate.consume(detector, 8))
        val afterUnlock = gate.issue(7)
        assertFalse(gate.consume(afterUnlock, 9)) // A->B->A still has a new epoch.
    }

    @Test fun queuedStartPermissionAndAfterSpeakCannotSurviveRevocation() {
        for (continuation in listOf("queued 300ms", "permission", "afterSpeak", "recognizer fallback", "send completion")) {
            val gate = VoiceSessionGate()
            val original = gate.begin(1, wake = true)
            val resume = { gate.valid(original, 1, wakeEnabled = true) }
            assertTrue(continuation, resume())
            gate.invalidate()
            assertFalse(continuation, resume())
            val freshTap = gate.begin(1, wake = false)
            assertTrue(gate.valid(freshTap, 1, wakeEnabled = false))
            assertFalse("$continuation revived by new tap", resume())
        }
    }

    @Test fun pendingCaptureAndRunningSessionRejectMachineSwitch() {
        val gate = VoiceSessionGate()
        val pending = gate.begin(4, wake = true)
        assertFalse(gate.valid(pending, 5, wakeEnabled = true))
        assertFalse(gate.valid(pending, 6, wakeEnabled = true))
        val manual = gate.begin(6, wake = false)
        assertFalse(gate.valid(manual, 7, wakeEnabled = true))
    }

    @Test fun optOutRevokesWakeButManualTalkRemainsUsable() {
        val gate = VoiceSessionGate()
        val wake = gate.begin(1, wake = true)
        assertFalse(gate.valid(wake, 1, wakeEnabled = false))
        gate.invalidate() // The preference listener permanently revokes on opt-out.
        assertFalse(gate.valid(wake, 1, wakeEnabled = true))
        val tap = gate.begin(1, wake = false)
        assertTrue(gate.valid(tap, 1, wakeEnabled = false))
    }
}
