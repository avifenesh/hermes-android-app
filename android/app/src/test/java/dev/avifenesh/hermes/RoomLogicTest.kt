package dev.avifenesh.hermes

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class RoomLogicTest {
    private fun ev(seq: Long, role: String, member: String = "", ts: Double = 1000.0 + seq, kind: String = "message",
                   notice: String = "", detail: String = "") =
        RoomEvent(seq, "e$seq", kind, role, member, member.replaceFirstChar { it.uppercase() }, "text $seq", ts, "t", notice, detail, emptyList())

    private fun room(unread: Int, latest: Long, role: String = "member", id: String = "r") =
        Room(id, "Crew", emptyList(), latest, 0, unread, 0.0, emptyList(), RoomPreview(latest, role, "Scout", "hi", 0.0))

    @Test fun roomsDefaultOnForOldServersAndGateEveryRoomRoute() {
        assertTrue(Features().rooms)
        assertTrue(Features.from(mapOf("rooms" to null)).rooms)
        val off = Features.from(mapOf("rooms" to false))
        for (p in listOf("/v1/rooms", "/v1/rooms?x=1", "/v1/rooms/a", "/v1/rooms/a/messages", "/v1/rooms/a/files/e/att")) {
            assertFalse(p, off.allows(p))
            assertTrue(p, Features().allows(p))
        }
        for (p in listOf("/v1/roomsx", "/v1/chats", "/v1/sync")) assertTrue(p, off.allows(p))
        assertTrue(off.fleet && off.radar)  // turning rooms off touches no other gate
    }

    @Test fun parsesTheRoomSummaryNullSafely() {
        val o = JSONObject().put("id", "crew").put("name", JSONObject.NULL).put("latest_seq", 9).put("read_seq", 4).put("unread", 2)
            .put("updated", 12.5).put("working", JSONArray(listOf("scout")))
            .put("members", JSONArray().put(JSONObject().put("id", "scout").put("handle", "scout").put("name", JSONObject.NULL))
                .put(JSONObject().put("id", "scribe").put("profile", "writer").put("handle", "scribe").put("name", "Scribe")))
            .put("preview", JSONObject().put("seq", 9).put("role", "member").put("name", "Scribe").put("text", "Done.").put("ts", 12.0))
        val r = RoomLogic.room(o)
        assertEquals("Group chat", r.name)  // never the string "null"
        assertEquals(listOf("scout", "Scribe"), r.members.map { it.name })
        assertEquals(listOf("scout", "writer"), r.members.map { it.profile })
        assertEquals(Triple(9L, 4L, 2), Triple(r.latestSeq, r.readSeq, r.unread))
        assertEquals(listOf("scout"), r.working)
        assertEquals("Scribe: Done.", RoomLogic.previewLine(r))
        assertNull(RoomLogic.room(JSONObject().put("id", "x").put("preview", JSONObject.NULL)).preview)
        assertEquals("You: plan it", RoomLogic.previewLine(r.copy(preview = RoomPreview(1, "user", "", "plan it", 0.0))))
    }

    @Test fun parsesDriverStateAndPendingApproval() {
        val d = RoomLogic.driver(JSONObject().put("running", true).put("working", true)
            .put("members", JSONArray().put(JSONObject().put("id", "scout").put("status", "running")))
            .put("pending", JSONArray().put(JSONObject().put("kind", "approval").put("member", "scout").put("task_id", "t")
                .put("generation", 3).put("request_id", "r").put("command", "rm -rf build"))))
        assertEquals(mapOf("scout" to "running"), d.members)
        assertEquals(RoomAction("approval", "scout", "t", 3, "r", "rm -rf build", ""), d.pending.single())
        assertFalse(RoomLogic.driver(null).running)
    }

    @Test fun pagesMergeByLogSequence() {
        val newer = listOf(ev(5, "member", "scout"), ev(7, "user"))
        val older = listOf(ev(2, "user"), ev(5, "member", "scout"))
        val merged = RoomLogic.merge(newer, older)
        assertEquals(listOf(2L, 5L, 7L), merged.map { it.seq })
        assertEquals(merged, RoomLogic.merge(merged, older))  // a repeated page changes nothing
        assertEquals(newer, RoomLogic.merge(newer, emptyList()))
    }

    @Test fun timelineGroupsSpeakerRunsAndFoldsNotices() {
        val day = { ts: Double -> if (ts < 5000) "Mon" else "Tue" }
        val rows = RoomLogic.rows(listOf(
            ev(1, "user"),
            ev(2, "member", "scout"), ev(3, "member", "scout"),              // one run: one name and avatar
            ev(4, "member", "scribe"),                                       // a new speaker opens a run
            ev(5, "", "scribe", kind = "notice", notice = "turn.failed"),
            ev(6, "", "critic", kind = "notice", notice = "turn.cancelled"), // folded into the row above
            ev(7, "member", "scribe"),                                       // a notice breaks the run
            ev(8, "member", "scribe", ts = 1008.0 + 600),                    // ten minutes later: a new run
            ev(9, "member", "scribe", ts = 6000.0),                          // a new day
        ), day)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 7L, 8L, 9L), rows.map { it.event.seq })
        assertEquals(listOf(true, true, false, true, true, true, true, true), rows.map { it.head })
        assertEquals(listOf(true, false, true, true, true, true, true, true), rows.map { it.tail })
        assertEquals(listOf(5L, 6L), rows[4].notices.map { it.seq })
        assertEquals(listOf(true, false, false, false, false, false, false, true), rows.map { it.day })
    }

    @Test fun unreadBadgesAndNotificationsFollowChatRules() {
        assertEquals(2, RoomLogic.unreadRooms(listOf(room(3, 9), room(0, 4), room(1, 2))))
        assertFalse(RoomLogic.notifies(null, room(3, 9), foreground = false))       // first sight: old replies are not news
        assertTrue(RoomLogic.notifies(room(1, 8), room(2, 9), foreground = false))
        assertFalse(RoomLogic.notifies(room(1, 8), room(2, 9), foreground = true))  // like chats: no ping in the app
        assertFalse(RoomLogic.notifies(room(2, 8), room(2, 9), foreground = false)) // the owner's own words add nothing
        assertFalse(RoomLogic.notifies(room(1, 8), room(2, 9, role = "user"), foreground = false))
        assertFalse(RoomLogic.notifies(room(2, 9), room(0, 9), foreground = false)) // read elsewhere
    }

    @Test fun noticesReadAsSentences() {
        assertEquals("Scout hit an error: provider timeout", RoomLogic.noticeText(
            ev(1, "", "scout", kind = "notice", notice = "turn.failed", detail = "provider timeout\ntraceback ...")))
        assertEquals("Scout moved on to your newer message", RoomLogic.noticeText(
            ev(1, "", "scout", kind = "notice", notice = "turn.cancelled", detail = "superseded_by_newer_user_event")))
        assertEquals("Renamed to Release crew", RoomLogic.noticeText(
            ev(1, "", "", kind = "notice", notice = "room.renamed", detail = "Release crew")))
    }

    @Test fun mentionsAddTheHandleOnce() {
        assertEquals("@scout ", RoomLogic.mention("", "scout"))
        assertEquals("@scout check this", RoomLogic.mention("  check this", "scout"))
        assertEquals("ask @scout now", RoomLogic.mention("ask @scout now", "scout"))
        assertEquals("@scout @scouting x", RoomLogic.mention("@scouting x", "scout"))
    }

    @Test fun workingLineNamesWhoAnswersAndColorsStayPerMember() {
        val members = listOf(RoomMember("a", "a", "a", "Ada"), RoomMember("b", "b", "b", "Bo"), RoomMember("c", "c", "c", "Cy"))
        assertEquals(listOf("Ada", "Cy"), RoomLogic.answering(members, setOf("c", "a")))
        assertEquals("", RoomLogic.workingLine(emptyList()))
        assertEquals("Ada is answering", RoomLogic.workingLine(listOf("Ada")))
        assertEquals("Ada and Cy are answering", RoomLogic.workingLine(listOf("Ada", "Cy")))
        assertEquals("Ada, Bo and Cy are answering", RoomLogic.workingLine(listOf("Ada", "Bo", "Cy")))
        val colors = members.map { RoomLogic.colorIndex(members, it.id, 5) }
        assertEquals(listOf(1, 2, 3), colors)  // index 0 is the app accent, kept for the owner's controls
        // A speaker no longer in the roster keeps a color from its id alone, in range.
        assertEquals(("zed".hashCode() and 0x7fffffff) % 5, RoomLogic.colorIndex(members, "zed", 5))
        assertEquals(RoomLogic.colorIndex(emptyList(), "zed", 5), RoomLogic.colorIndex(members.reversed(), "zed", 5))
        assertEquals("S", RoomLogic.initial(" @scout"))
    }

    private fun approval(task: String, request: String, member: String = "scout") =
        RoomAction("approval", member, task, 1, request, "rm -rf build", "")

    @Test fun pendingApprovalsParseBadgeAndNotifyOnce() {
        val o = JSONObject().put("id", "crew").put("pending", JSONArray()
            .put(JSONObject().put("kind", "approval").put("member", "scout").put("task_id", "t1").put("generation", 2)
                .put("request_id", "r1").put("command", "rm -rf build"))
            .put(JSONObject().put("kind", "discard").put("member", "scribe").put("task_id", "t2").put("generation", 1)))
        val r = RoomLogic.room(o)
        assertEquals(listOf("approval", "discard"), r.pending.map { it.kind })
        assertEquals(RoomAction("approval", "scout", "t1", 2, "r1", "rm -rf build", ""), r.pending[0])
        assertTrue(RoomLogic.needsYou(r))
        assertFalse(RoomLogic.needsYou(r.copy(pending = r.pending.drop(1))))  // a discard waits, but asks nothing
        val first = room(0, 1).copy(pending = listOf(approval("t1", "r1")))
        assertEquals(listOf("r1"), RoomLogic.newApprovals(null, first).map { it.requestId })  // first sight asks
        assertEquals(emptyList<RoomAction>(), RoomLogic.newApprovals(first, first))        // the next poll does not
        val second = first.copy(pending = listOf(approval("t1", "r1"), approval("t3", "r3", "scribe")))
        assertEquals(listOf("r3"), RoomLogic.newApprovals(first, second).map { it.requestId })
        assertEquals(listOf("r1"), RoomLogic.answeredApprovals(second, first.copy(pending = listOf(approval("t3", "r3", "scribe"))))
            .map { it.requestId })
        assertEquals(listOf("r1", "r3"), RoomLogic.answeredApprovals(second, null).map { it.requestId })  // room deleted
        assertTrue(RoomLogic.asksOutside("crew", foreground = true, open = "other"))
        assertTrue(RoomLogic.asksOutside("crew", foreground = false, open = "crew"))
        assertFalse(RoomLogic.asksOutside("crew", foreground = true, open = "crew"))  // already looking at it
    }

    @Test fun aSummaryApprovalShowsButOnlyTheFullDetailCanBeAnswered() {
        val cut = RoomLogic.action(JSONObject().put("kind", "approval").put("member", "scout").put("task_id", "t1")
            .put("generation", 1).put("request_id", "r1").put("command", "x".repeat(300)).put("command_truncated", true))
        assertTrue(cut.truncated)
        val discard = RoomAction("discard", "scribe", "t2", 1, "", "", "")
        val room = room(0, 1).copy(pending = listOf(cut, discard))
        assertEquals(listOf(false, true), RoomLogic.cards(null, room).map { it.ready })  // detail not loaded yet
        val full = cut.copy(command = "x".repeat(5000), truncated = false)
        val driver = RoomDriver(true, true, false, emptyMap(), listOf(full))
        assertEquals(listOf(RoomCard(full, true)), RoomLogic.cards(driver, room))  // the detail replaces the summary
    }

    @Test fun theReadCursorMovesOnceTheRoomHasLanded() {
        val short = room(2, 9)  // every row fits: nearEnd is true from the first frame
        assertNull(RoomLogic.readTarget(short, nearEnd = true, landed = false))
        assertEquals(9L, RoomLogic.readTarget(short, nearEnd = true, landed = true))
        assertNull(RoomLogic.readTarget(short, nearEnd = false, landed = true))
        assertNull(RoomLogic.readTarget(short.copy(unread = 0, readSeq = 9), nearEnd = true, landed = true))
        assertNull(RoomLogic.readTarget(null, nearEnd = true, landed = true))
    }

    @Test fun aRetryKeepsItsIdOnlyWhileTheMessageIsUnchanged() {
        var n = 0
        val fresh = { "id-${++n}" }
        val a = JSONObject().put("id", "u-a.txt")
        val b = JSONObject().put("id", "u-b.txt")
        val first = RoomLogic.sendId(null, "hello", listOf(a, b), fresh)
        assertEquals("id-1", first.second)
        assertEquals(first, RoomLogic.sendId(first, "hello", listOf(a, b), fresh))  // a retry is the same send
        assertEquals("id-2", RoomLogic.sendId(first, "hello", listOf(b), fresh).second)  // a file removed
        assertEquals("id-3", RoomLogic.sendId(first, "hello!", listOf(a, b), fresh).second)  // text edited
        assertEquals("id-4", RoomLogic.sendId(first, "hello", listOf(b, a), fresh).second)  // files reordered
    }

    @Test fun aSendKeepsWhatWasTypedWhileItWasInFlight() {
        assertEquals("", RoomLogic.draftAfterSend("plan it", "plan it"))
        assertEquals("and the tests", RoomLogic.draftAfterSend("plan it and the tests", "plan it"))
        assertEquals("something else", RoomLogic.draftAfterSend("something else", "plan it"))
    }

    @Test fun earlierMessagesShowEvenWhenTheNewestPageWasBookkeeping() {
        assertTrue(RoomLogic.olderControl(hasOlder = true, error = null))
        assertFalse(RoomLogic.olderControl(hasOlder = false, error = null))
        assertFalse(RoomLogic.olderControl(hasOlder = true, error = "offline"))
    }

    @Test fun aMissingRouteIsAnAnswerPublishedUnderTheGuards() = runBlocking {
        val missing: suspend () -> JSONObject = { throw ApiError(404, "HTTP 404") }
        var published: JSONObject? = null
        val fresh = loadCapability({ true }, SessionEpoch(), SessionEpoch(), { roomListAnswer(missing) }) { published = it }
        assertFalse(fresh!!.optBoolean("supported", true))
        assertEquals(0, published!!.getJSONArray("rooms").length())
        // A 404 that arrives after a switch to another machine publishes nothing.
        val session = SessionEpoch()
        val gate = CompletableDeferred<Unit>()
        var late: JSONObject? = null
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            loadCapability({ true }, session, SessionEpoch(), { gate.await(); roomListAnswer(missing) }) { late = it }
        }
        session.invalidate()
        gate.complete(Unit)
        try { job.await(); fail("a stale 404 was published") } catch (_: CancellationException) { }
        assertNull(late)
        try { roomListAnswer { throw ApiError(500, "HTTP 500") }; fail("other errors are not answers") } catch (e: ApiError) {
            assertEquals(500, e.code)
        }
    }

    @Test fun aQueuedReadNeverReachesAnotherMachine() = runBlocking {
        val session = SessionEpoch()
        val gate = SessionEpoch()
        val sent = ArrayList<String>()
        // Captured when the room was read on machine A.
        val read = MachineBound(session, session.capture(), gate, gate.capture())
        session.invalidate()  // the phone switched to machine B before the queued post started
        try {
            read.send({ "POST /v1/rooms/crew/read on ${session.capture()}" }) { sent += it }
            fail("a read captured on one machine was sent on another")
        } catch (_: CancellationException) { }
        val gated = MachineBound(session, session.capture(), gate, gate.capture())
        gate.invalidate()  // rooms turned off and on again
        try { gated.send({ "late" }) { sent += it }; fail("a read crossed a capability change") } catch (_: CancellationException) { }
        assertEquals(emptyList<String>(), sent)  // nothing was built, so nothing was signed or posted
        val current = MachineBound(session, session.capture(), gate, gate.capture())
        current.send({ "POST /v1/rooms/crew/read" }) { sent += it }
        assertEquals(listOf("POST /v1/rooms/crew/read"), sent)
    }
}
