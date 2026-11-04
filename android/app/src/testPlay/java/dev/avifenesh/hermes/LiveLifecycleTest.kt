package dev.avifenesh.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Play run-progress service against a model of the platform rules it depends on: startForegroundService owes
 * startForeground, stopSelfResult(id) refuses while a newer start is pending, a bare stop with a start owed crashes
 * ("did not then call Service.startForeground()"), and onDestroy arrives after the stop.
 */
class LiveLifecycleTest {
    private class Platform : LiveLifecycle.Host {
        override var paired = true
        override var foreground = true
        override var talkActive = false
        var refuse = false
        var connected = false
        var requests = 0
        var issued = 0
        val pending = ArrayDeque<Pair<Int, Boolean>>() // start id, true = startForegroundService
        val destroyed = ArrayDeque<Svc>()
        val crashes = mutableListOf<String>()
        var svc: Svc? = null
        lateinit var life: LiveLifecycle

        override fun requestStart() {
            requests++
            if (refuse) throw IllegalStateException("ForegroundServiceStartNotAllowedException")
            pending.addLast(++issued to true)
        }
        override fun connect() { connected = true }
        override fun disconnect() { connected = false }

        /** "Stop updates": a plain startService. */
        fun stopAction() { pending.addLast(++issued to false) }

        /** Deliver queued onStartCommand and onDestroy calls in order, as the main looper would. */
        fun settle() {
            while (pending.isNotEmpty() || destroyed.isNotEmpty()) {
                destroyed.removeFirstOrNull()?.let { life.onDestroy(it); continue }
                deliverOne()
            }
        }

        /** One onStartCommand, the oldest pending start. */
        fun deliverOne() {
            val (id, fg) = pending.removeFirst()
            val s = svc ?: Svc(this).also { svc = it }
            if (fg) s.owed = true
            life.onStart(s, id, stopAction = !fg)
            if (s.owed && svc === s) crashes += "start $id never promoted"
        }
    }

    private class Svc(val p: Platform) : LiveLifecycle.Instance {
        var owed = false
        var foreground = false
        var notification = false
        var notifiesAfterStop = 0
        var stopped = false
        var promotions = 0
        /** Android 15+ re-checks background-start eligibility on every startForeground with a time-limited type,
         *  even for a service that is already foreground. The start's obligation is consumed before the throw. */
        var refusePromote = false
        override fun promote() {
            owed = false
            if (refusePromote) throw IllegalStateException("ForegroundServiceStartNotAllowedException")
            promotions++; foreground = true; notification = true
        }
        override fun demote() { foreground = false; notification = false }
        override fun stopIfLatest(startId: Int): Boolean {
            // stopSelf() passes -1 and always stops; stopSelfResult(id) refuses when a newer start was issued.
            if (startId >= 0 && startId != p.issued) return false
            if (owed || p.pending.any { it.second }) p.crashes += "stopped while a start owes startForeground"
            stopped = true; p.svc = null; p.destroyed.addLast(this)
            return true
        }
        override fun notifyProgress() { notification = true; if (stopped) notifiesAfterStop++ }
    }

    private fun setup(): Pair<Platform, LiveLifecycle> {
        val p = Platform()
        val life = LiveLifecycle(RunFollow(30_000, 6 * 3_600_000L), p)
        p.life = life
        return p to life
    }

    private fun LiveLifecycle.send(chat: String, seq: Long, now: Long): LiveLifecycle.Follow =
        LiveLifecycle.Follow(chat).also { starting(it, seq, now, nextTurn = true); started(it, seq, now) }

    @Test fun pairingPushAndMachineSwitchNeverHoldTheServiceOrTheStream() {
        val (p, life) = setup()
        life.firstPair(); life.afterPair(push = false); life.pushReady(); life.request()
        p.settle()
        assertEquals("no service without a run", 0, p.requests)
        assertFalse(life.runs.active())
        assertFalse("keepConnected is never true without a run", life.keepConnected())
        p.talkActive = true
        assertTrue("an active Talk conversation needs its replies", life.keepConnected())
    }

    @Test fun aSendFollowsBeforeTheRequestReturnsAndAFailureUndoesIt() {
        val (p, life) = setup()
        val f = LiveLifecycle.Follow("c1")
        life.starting(f, afterSeq = 5, now = 0, nextTurn = true)
        assertEquals("the start is requested while the app is still in the foreground", 1, p.requests)
        p.settle()
        assertTrue(p.svc!!.foreground)
        p.foreground = false
        life.abandon(f) // the POST failed
        assertNull(life.step(10, online = true, busy = emptySet()) { null })
        p.settle()
        assertTrue(p.crashes.isEmpty())
        assertNull("the service stopped", p.svc)
        assertFalse(p.connected)
    }

    @Test fun abandonKeepsARunThatAnotherSendOrTheGatewayConfirmed() {
        val (_, life) = setup()
        life.send("c1", 1, 0)
        life.step(1, online = true, busy = setOf("c1")) { null }
        val second = LiveLifecycle.Follow("c1")
        life.starting(second, 3, 2, nextTurn = true)
        life.abandon(second)
        assertTrue(life.runs.active())
    }

    @Test fun aStartRequestedWhileTheServiceStopsIsNeitherLostNorCrashes() {
        val (p, life) = setup()
        life.send("c1", 1, 0)
        // A notification reply asks for a start before the first one has landed, so both are pending.
        life.send("c2", 2, 5)
        p.deliverOne()
        val first = p.svc!!
        // A machine switch clears every run while the later starts are still pending.
        life.runs.clear()
        assertNull(life.step(6, online = true, busy = emptySet()) { null })
        assertFalse("a newer start is pending, so the service may not stop yet", first.stopped)
        p.settle()
        assertTrue(p.crashes.toString(), p.crashes.isEmpty())
        assertNull("the pending start promoted, saw nothing to follow and stopped", p.svc)
    }

    @Test fun aSendAfterTheStopDecisionStartsAFreshService() {
        val (p, life) = setup()
        life.send("c1", 1, 0)
        p.settle()
        life.step(1, online = true, busy = setOf("c1")) { null }
        assertNull(life.step(2, online = true, busy = emptySet()) { null })
        life.send("c2", 3, 3)
        p.settle()
        assertTrue(p.crashes.isEmpty())
        assertTrue("the new run has a foreground service", p.svc?.foreground == true)
        assertTrue(life.runs.active())
    }

    @Test fun stopUpdatesWhileAStartIsPendingNeverCrashes() {
        val (p, life) = setup()
        life.send("c1", 1, 0)
        p.settle()
        life.send("c1", 2, 1)
        p.stopAction()
        p.settle()
        assertTrue(p.crashes.toString(), p.crashes.isEmpty())
        assertFalse(life.runs.active())
        assertNull(p.svc)
    }

    @Test fun noProgressNotificationAfterTheServiceStops() {
        val (p, life) = setup()
        life.send("c1", 1, 0)
        p.settle()
        val s = p.svc!!
        life.step(1, online = true, busy = setOf("c1")) { null }
        life.step(2, online = true, busy = emptySet()) { null }
        assertFalse("ending cancels the progress notification", s.notification)
        life.post() // a late loop pass
        assertFalse(s.notification)
        assertEquals(0, s.notifiesAfterStop)
    }

    @Test fun aStaleWorkingSetWhileOfflineDoesNotHoldTheService() {
        val (p, life) = setup()
        life.send("c1", 1, 0)
        p.settle()
        life.step(1, online = true, busy = setOf("c1")) { null }
        // The gateway became unreachable; Hub.working still lists c1 from before the drop.
        assertTrue(life.step(1_000, online = false, busy = setOf("c1")) { null } != null)
        assertNull("the grace ran out without a reconnect", life.step(31_000, online = false, busy = setOf("c1")) { null })
        p.settle()
        assertNull(p.svc)
    }

    @Test fun theReconnectHelloReconfirmsALiveRun() {
        val (p, life) = setup()
        life.send("c1", 1, 0)
        p.settle()
        life.step(1, online = true, busy = setOf("c1")) { null }
        life.step(1_000, online = false, busy = setOf("c1")) { null }
        life.step(5_000, online = true, busy = setOf("c1")) { null }
        assertTrue("re-confirmed: the start grace no longer applies", life.step(60_000, online = true, busy = setOf("c1")) { null } != null)
        assertTrue(p.svc?.foreground == true)
    }

    @Test fun aRefusedStartDropsTheFollowAndReleasesTheStream() {
        val (p, life) = setup()
        p.foreground = false
        p.refuse = true
        p.connected = true
        life.send("c1", 1, 0)
        assertFalse(life.runs.active())
        assertFalse(p.connected)
        assertFalse(life.keepConnected())
    }

    @Test fun aSendWhileTheServiceRunsDoesNotPromoteAgain() {
        val (p, life) = setup()
        life.send("c1", 1, 0)
        p.settle()
        val s = p.svc!!
        val starts = p.requests
        val promotions = s.promotions
        p.foreground = false // the POST answers after the user left
        life.send("c2", 2, 10)
        life.started(LiveLifecycle.Follow("c1"), 3, 11)
        p.settle()
        assertEquals("no startForegroundService while already promoted", starts, p.requests)
        assertEquals("no re-promotion that Android 15 could refuse", promotions, s.promotions)
        assertEquals(listOf("c1", "c2"), life.runs.followed().sorted())
        assertTrue(life.step(12, online = true, busy = setOf("c1", "c2")) { null } != null)
        assertTrue(s.foreground)
    }

    @Test fun aRefusedRePromotionWhileForegroundKeepsFollowing() {
        val (p, life) = setup()
        life.send("c1", 1, 0)
        p.settle()
        val s = p.svc!!
        s.refusePromote = true
        p.foreground = false
        // A start Android still delivers (requested before the service promoted, for example).
        p.pending.addLast(++p.issued to true)
        p.settle()
        assertTrue(p.crashes.toString(), p.crashes.isEmpty())
        assertTrue("already foreground: keep following", life.runs.active())
        assertTrue(s.foreground && !s.stopped)
        assertTrue(life.step(1, online = true, busy = setOf("c1")) { null } != null)
    }

    @Test fun aRefusedFirstPromotionEndsCleanly() {
        val (p, life) = setup()
        val f = LiveLifecycle.Follow("c1")
        life.starting(f, 1, 0, nextTurn = true)
        p.svc = Svc(p).also { it.refusePromote = true }
        p.settle()
        assertTrue(p.crashes.toString(), p.crashes.isEmpty())
        assertFalse("no service can carry it", life.runs.active())
        assertNull(p.svc)
    }

    @Test fun aRefusedStartDropsOnlyTheFollowThatAskedForIt() {
        val (p, life) = setup()
        life.send("c1", 1, 0) // its start is still pending
        p.foreground = false
        p.refuse = true
        life.send("c2", 2, 5)
        assertEquals(listOf("c1"), life.runs.followed())
        p.refuse = false
        p.settle()
        assertTrue(p.svc?.foreground == true)
        assertTrue(p.crashes.isEmpty())
    }

    @Test fun aWorkingSignalBeforeTheResponseKeepsTheSendItsOwnTurn() {
        val (p, life) = setup()
        val f = LiveLifecycle.Follow("c1")
        life.starting(f, 1, 0, nextTurn = true) // an idle chat: this send starts its own turn
        p.settle()
        // working:true lands before the POST response (profile chats emit it inside the request).
        assertTrue(life.step(10, online = true, busy = setOf("c1")) { null } != null)
        life.started(f, 2, 20)
        assertNull("the run ends with its own turn, not after a queued-turn grace",
            life.step(30, online = true, busy = emptySet()) { null })
    }

    @Test fun aSendIntoAWorkingChatWaitsForTheTurnItQueued() {
        val (p, life) = setup()
        life.send("c1", 1, 0)
        p.settle()
        life.step(1, online = true, busy = setOf("c1")) { null }
        val queued = LiveLifecycle.Follow("c1")
        life.starting(queued, 5, 40, nextTurn = true) // decided here: the chat is working when the user sends
        life.step(45, online = true, busy = setOf("c1")) { null }
        life.started(queued, 6, 50)
        assertTrue("between turns", life.step(60, online = true, busy = emptySet()) { null } != null)
        life.step(61, online = true, busy = setOf("c1")) { null }
        assertNull(life.step(62, online = true, busy = emptySet()) { null })
    }

    @Test fun aQueuedTurnSurvivesAReconnectBeforeTheHello() {
        val (p, life) = setup()
        life.send("c1", 1, 0)
        p.settle()
        life.step(1, online = true, busy = setOf("c1")) { null }
        val queued = LiveLifecycle.Follow("c1")
        life.starting(queued, 5, 2, nextTurn = true)
        life.started(queued, 6, 3)
        life.step(100, online = true, busy = emptySet()) { null } // the first turn ended
        life.step(1_000, online = false, busy = emptySet()) { null }
        assertTrue("onOpen, before the hello's working set", life.step(2_000, online = true, busy = emptySet()) { null } != null)
        assertTrue(life.step(3_000, online = true, busy = setOf("c1")) { null } != null)
        assertTrue(p.svc?.foreground == true)
    }

    @Test fun timeoutStopsAndRefusesBackgroundStartsUntilForeground() {
        val (p, life) = setup()
        life.send("c1", 1, 0)
        p.settle()
        p.foreground = false
        life.onTimeout()
        p.settle()
        assertNull(p.svc)
        val before = p.requests
        life.send("c2", 2, 10)
        assertEquals("no start while the budget is exhausted", before, p.requests)
        p.foreground = true
        life.send("c3", 3, 20)
        assertTrue("opening the app resets the budget", p.requests > before)
    }
}
