package dev.avifenesh.hermes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CapabilityLifecycleTest {
    @Test fun defaultsRemainOnBeforeFetchAndForOldServers() {
        assertEquals(Features(true, true, true, true), Features())
        assertEquals(Features(), Features.from())
        assertEquals(Features(), Features.from(mapOf("radar" to null)))
        assertEquals(Features(fleet = false), Features.from(mapOf("fleet" to false)))
    }

    @Test fun disabledRoutesIncludeDetailsWritesAndQueriesButNotCoreWake() {
        val off = Features(false, false, false, false)
        for (p in listOf("/v1/fleet", "/v1/fleet?recent=1", "/v1/fleet/a/send",
            "/v1/fleet/a/stop", "/v1/fleet/a/tail?n=80", "/v1/radar/notes/a/ack",
            "/v1/projects/repo/push", "/v1/wake/whatsapp-test")) {
            assertFalse(p, off.allows(p))
            assertTrue(p, Features().allows(p))
        }
        for (p in listOf("/v1/sync", "/v1/me", "/v1/wake", "/v1/wake/proof", "/v1/fleet-other")) {
            assertTrue(p, off.allows(p))
        }
    }

    @Test fun offDoesNotFetchOrReturnCachedSnapshot() = runBlocking {
        var fetched = false
        var cached = "old events"
        val result = loadCapability({ false }, SessionEpoch(), SessionEpoch(),
            { fetched = true; "new events" }, { cached = it })
        assertNull(result)
        assertFalse(fetched)
        assertEquals("old events", cached) // Caller receives null, never the stale notification payload.
    }

    @Test fun disabledDuringFetchDoesNotPublish() = runBlocking {
        var enabled = true
        var cached: String? = null
        val response = CompletableDeferred<String>()
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            loadCapability({ enabled }, SessionEpoch(), SessionEpoch(), { response.await() }, { cached = it })
        }
        enabled = false
        response.complete("stale")
        assertNull(job.await())
        assertNull(cached)
    }

    @Test fun offOnCycleRejectsOldResponse() = runBlocking {
        val gate = SessionEpoch()
        val response = CompletableDeferred<String>()
        var published = false
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            loadCapability({ true }, SessionEpoch(), gate, { response.await() }, { published = true })
        }
        gate.invalidate() // off
        gate.invalidate() // on, same boolean as at request start
        response.complete("stale")
        try { job.await(); fail("An old capability generation was accepted") } catch (_: CancellationException) { }
        assertFalse(published)
    }

    @Test fun switchingAwayAndBackRejectsOldMachineResponse() = runBlocking {
        val session = SessionEpoch()
        val response = CompletableDeferred<String>()
        var published = false
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            loadCapability({ true }, session, SessionEpoch(), { response.await() }, { published = true })
        }
        session.invalidate()
        session.invalidate()
        response.complete("machine A's old response")
        try { job.await(); fail("An old connection generation was accepted") } catch (_: CancellationException) { }
        assertFalse(published)
    }

    @Test fun cancellationDoesNotPublish() = runBlocking {
        val response = CompletableDeferred<String>()
        var published = false
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            loadCapability({ true }, SessionEpoch(), SessionEpoch(), { response.await() }, { published = true })
        }
        job.cancelAndJoin()
        response.complete("too late")
        assertFalse(published)
    }

    @Test fun offlineFailurePreservesLastGoodStateButDoesNotReturnItAsFreshEvents() = runBlocking {
        var cached = "last good"
        try {
            loadCapability<String>({ true }, SessionEpoch(), SessionEpoch(), { throw IOException("offline") }, { cached = it })
            fail("Offline must fail rather than replay cached events")
        } catch (_: IOException) { }
        assertEquals("last good", cached)
        assertEquals("fresh", loadCapability({ true }, SessionEpoch(), SessionEpoch(), { "fresh" }, { cached = it }))
        assertEquals("fresh", cached)
    }

    @Test fun rejectedCommitHasNoSideEffects() {
        val epoch = SessionEpoch()
        val old = epoch.capture()
        epoch.invalidate()
        var writes = 0
        try { epoch.commit(old) { writes++ }; fail("Old response committed") } catch (_: CancellationException) { }
        epoch.commit(epoch.capture()) { writes++ }
        assertEquals(1, writes)
    }
}
