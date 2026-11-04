package dev.avifenesh.hermes

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** One Bot seated in a gateway-hosted room. */
data class RoomMember(val id: String, val profile: String, val handle: String, val name: String)
data class RoomPreview(val seq: Long, val role: String, val name: String, val text: String, val ts: Double)
data class Room(val id: String, val name: String, val members: List<RoomMember>, val latestSeq: Long, val readSeq: Long,
                val unread: Int, val updated: Double, val working: List<String>, val preview: RoomPreview?,
                val pending: List<RoomAction> = emptyList())

/** A room-log entry as the plugin projects it: a message (`role` user or member) or a thin notice. */
data class RoomEvent(val seq: Long, val id: String, val kind: String, val role: String, val member: String, val name: String,
                     val text: String, val ts: Double, val thread: String, val notice: String, val detail: String,
                     val attachments: List<JSONObject>)

/** Work Hermes' room driver is waiting on: a member's command approval, or a turn to retry or discard. */
data class RoomAction(val kind: String, val member: String, val taskId: String, val generation: Long,
                      val requestId: String, val command: String, val description: String, val truncated: Boolean = false)

/** An action card and whether it may be answered: a summary's approval (its command cut short) only shows. */
data class RoomCard(val action: RoomAction, val ready: Boolean)
data class RoomDriver(val running: Boolean, val working: Boolean, val blocked: Boolean,
                      val members: Map<String, String>, val pending: List<RoomAction>)
data class RoomBot(val profile: String, val name: String)

/** How one timeline row draws: who speaks, and whether it opens a day, a speaker run or a notice run. */
data class RoomRowSpec(val event: RoomEvent, val day: Boolean, val head: Boolean, val tail: Boolean, val notices: List<RoomEvent>)

/** Pure parsing and layout rules for group chats (JVM-tested; no Android or network). */
object RoomLogic {
    private const val RUN_GAP_S = 5 * 60

    fun member(o: JSONObject) = RoomMember(o.str("id"), o.str("profile").ifBlank { o.str("id") },
        o.str("handle").ifBlank { o.str("id") }, o.str("name").ifBlank { o.str("handle").ifBlank { o.str("id") } })

    fun room(o: JSONObject): Room {
        val p = o.optJSONObject("preview")
        return Room(o.str("id"), o.str("name").ifBlank { str(R.string.room_default_name) }, o.optJSONArray("members").objs().map(::member),
            o.optLong("latest_seq"), o.optLong("read_seq"), o.optInt("unread"), o.optDouble("updated", 0.0),
            o.optJSONArray("working").strs(),
            p?.let { RoomPreview(it.optLong("seq"), it.str("role"), it.str("name"), it.str("text"), it.optDouble("ts", 0.0)) },
            o.optJSONArray("pending").objs().map(::action))
    }

    fun action(o: JSONObject) = RoomAction(o.str("kind"), o.str("member"), o.str("task_id"), o.optLong("generation"),
        o.str("request_id"), o.str("command"), o.str("description"), o.optBoolean("command_truncated"))

    /** The room's driver detail answers; until it arrives the summary's actions show, and an approval among
     *  them cannot be answered: the owner approves exactly the command they can read in full. */
    fun cards(driver: RoomDriver?, room: Room?): List<RoomCard> =
        driver?.pending?.map { RoomCard(it, true) } ?: room?.pending.orEmpty().map { RoomCard(it, it.kind != "approval") }

    /** The read cursor moves once the room has landed on its newest row and something is unread. */
    fun readTarget(r: Room?, nearEnd: Boolean, landed: Boolean): Long? =
        r?.takeIf { nearEnd && landed && (it.unread > 0 || it.readSeq < it.latestSeq) }?.latestSeq

    /** One client id per intended message, bound to its exact text and files: an unchanged retry reuses it,
     *  any edit (text, a file added or removed) is a new message with a new id. */
    fun sendId(held: Pair<String, String>?, text: String, atts: List<JSONObject>, fresh: () -> String): Pair<String, String> {
        val print = text + "\u0000" + atts.joinToString("\u0000") { it.str("id").ifBlank { it.str("media") } }
        return if (held != null && held.first == print) held else print to fresh()
    }

    /** What stays in the composer after a send: whatever was typed while it was in flight. */
    fun draftAfterSend(current: String, sent: String): String = when {
        current == sent -> ""
        current.startsWith(sent) -> current.removePrefix(sent).trimStart()
        else -> current
    }

    /** "Earlier messages" shows whenever older history exists, even if the newest page held only bookkeeping. */
    fun olderControl(hasOlder: Boolean, error: String?): Boolean = hasOlder && error == null

    fun event(o: JSONObject) = RoomEvent(o.optLong("seq"), o.str("id"), o.str("kind", "message"), o.str("role"),
        o.str("member"), o.str("name"), o.str("text"), o.optDouble("ts", 0.0), o.str("thread"), o.str("notice"),
        o.str("detail"), o.optJSONArray("attachments").objs())

    fun driver(o: JSONObject?): RoomDriver {
        if (o == null) return RoomDriver(false, false, false, emptyMap(), emptyList())
        val members = o.optJSONArray("members").objs().associate { it.str("id") to it.str("status") }
        val pending = o.optJSONArray("pending").objs().map(::action)
        return RoomDriver(o.optBoolean("running"), o.optBoolean("working"), o.optBoolean("blocked"), members, pending)
    }

    /** Pages arrive older, newer and overlapping: one event per seq, oldest first. */
    fun merge(have: List<RoomEvent>, page: List<RoomEvent>): List<RoomEvent> {
        if (page.isEmpty()) return have
        val bySeq = LinkedHashMap<Long, RoomEvent>()
        for (e in have) bySeq[e.seq] = e
        for (e in page) bySeq[e.seq] = e
        return bySeq.values.sortedBy { it.seq }
    }

    /** Rooms with unread member replies, for the Inbox badge; the owner's own words never count. */
    fun unreadRooms(rooms: Collection<Room>): Int = rooms.count { it.unread > 0 }

    /** A room event notifies like a chat reply: a member said something new, unread grew, the app is in the
     *  background. A room seen for the first time (app start, reconnect) never notifies for old replies. */
    fun notifies(before: Room?, after: Room, foreground: Boolean): Boolean =
        before != null && after.latestSeq > before.latestSeq && after.unread > before.unread &&
            after.preview?.role == "member" && !foreground

    /** A member's command waits on the owner: the room badges like a chat with a pending approval. */
    fun needsYou(r: Room): Boolean = r.pending.any { it.kind == "approval" }

    private fun approvalKey(a: RoomAction) = "${a.taskId}:${a.generation}:${a.requestId}"

    /** Approvals that appeared since the last summary (all of them on first sight, as chat approvals after a sync). */
    fun newApprovals(before: Room?, after: Room): List<RoomAction> {
        val seen = before?.pending.orEmpty().filter { it.kind == "approval" }.map(::approvalKey).toSet()
        return after.pending.filter { it.kind == "approval" && approvalKey(it) !in seen }
    }

    /** Approvals the summary no longer carries: answered here, on another phone or on Desktop. */
    fun answeredApprovals(before: Room?, after: Room?): List<RoomAction> {
        val still = after?.pending.orEmpty().filter { it.kind == "approval" }.map(::approvalKey).toSet()
        return before?.pending.orEmpty().filter { it.kind == "approval" && approvalKey(it) !in still }
    }

    /** Ask the owner on the lock screen unless they are looking at that room already. */
    fun asksOutside(roomId: String, foreground: Boolean, open: String?): Boolean = !(foreground && open == roomId)

    /** Rows for the timeline: a day label when the date changes, the speaker's name and avatar on the first
     *  message of a run, consecutive notices folded into the first of them. */
    fun rows(events: List<RoomEvent>, dayOf: (Double) -> String): List<RoomRowSpec> {
        val out = ArrayList<RoomRowSpec>()
        var i = 0
        var lastDay: String? = null
        var prev: RoomEvent? = null
        while (i < events.size) {
            val e = events[i]
            val day = dayOf(e.ts)
            val newDay = day != lastDay
            lastDay = day
            if (e.kind == "notice") {
                val run = ArrayList<RoomEvent>()
                while (i < events.size && events[i].kind == "notice" && dayOf(events[i].ts) == day) run += events[i++]
                out += RoomRowSpec(e, newDay, true, true, run)
                prev = null
                continue
            }
            val head = newDay || prev == null || !sameSpeaker(prev, e)
            if (!head) out[out.lastIndex] = out.last().copy(tail = false)
            out += RoomRowSpec(e, newDay, head, true, emptyList())
            prev = e
            i++
        }
        return out
    }

    private fun sameSpeaker(a: RoomEvent, b: RoomEvent) =
        a.role == b.role && a.member == b.member && b.ts - a.ts <= RUN_GAP_S

    /** Members answering right now, by name, for the working line and the Inbox row. */
    fun answering(members: List<RoomMember>, working: Collection<String>): List<String> =
        members.filter { it.id in working }.map { it.name }

    fun workingLine(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> str(R.string.room_answering_one, names[0])
        2 -> str(R.string.room_answering_two, names[0], names[1])
        else -> str(R.string.room_answering_many, names.dropLast(1).joinToString(", "), names.last())
    }

    fun noticeText(e: RoomEvent): String {
        val who = e.name.ifBlank { str(R.string.room_notice_someone) }
        return when (e.notice) {
            "turn.failed" -> if (e.detail.isNotBlank()) str(R.string.room_notice_failed_detail, who, e.detail.lineSequence().first())
                else str(R.string.room_notice_failed, who)
            "turn.cancelled" -> if (e.detail == "superseded_by_newer_user_event") str(R.string.room_notice_superseded, who)
                else str(R.string.room_notice_cancelled, who)
            "turn.deferred" -> str(R.string.room_notice_deferred, who)
            "member.unavailable" -> str(R.string.room_notice_unavailable, who)
            "room.stop_requested" -> str(R.string.room_notice_stopped)
            "room.disbanded" -> str(R.string.room_notice_deleted)
            "room.renamed" -> if (e.detail.isNotBlank()) str(R.string.room_notice_renamed_to, e.detail) else str(R.string.room_notice_renamed)
            "authority.lost" -> str(R.string.room_notice_authority_lost)
            "room.bounded" -> str(R.string.room_notice_round_limit)
            else -> e.notice
        }
    }

    /** Address a member: their @handle goes in front of the draft, once. */
    fun mention(draft: String, handle: String): String {
        val tag = "@$handle"
        if (Regex("(^|\\s)" + Regex.escape(tag) + "(\\s|$)").containsMatchIn(draft)) return draft
        return if (draft.isBlank()) "$tag " else "$tag ${draft.trimStart()}"
    }

    fun previewLine(r: Room): String {
        val p = r.preview ?: return plural(R.plurals.rooms_member_count, r.members.size, r.members.size)
        val who = if (p.role == "user") str(R.string.common_you) else p.name.ifBlank { "Bot" }
        return "$who: ${p.text}"
    }

    /** Index into the accent presets so each member keeps one color in every room row and bubble. */
    fun colorIndex(members: List<RoomMember>, id: String, count: Int): Int {
        val i = members.indexOfFirst { it.id == id }
        return if (i >= 0) (i + 1) % count else (id.hashCode() and 0x7fffffff) % count
    }

    fun initial(name: String): String = name.trim().trimStart('@').take(1).uppercase().ifBlank { "#" }
}

/** The room-list fetch for [loadCapability]: a 404 (a plugin from before group chats, no such route) is an
 *  answer, so it is published under the same session and capability guards as any other list, never from an
 *  error handler a stale response from another machine or capability generation could reach. */
internal suspend fun roomListAnswer(fetch: suspend () -> JSONObject): JSONObject =
    try { fetch() } catch (e: ApiError) {
        if (e.code != 404) throw e
        JSONObject().put("supported", false).put("rooms", JSONArray()).put("bots", JSONArray())
    }

/** A request captured on one machine (session) and capability generation. [send] builds it inside both
 *  commits, so a request queued past a machine switch or a capability change is never built, signed or sent;
 *  a built request carries that same identity, which the signer checks again before the wire. */
internal class MachineBound(private val session: SessionEpoch, private val ticket: Long,
                            private val gate: SessionEpoch, private val gateTicket: Long) {
    suspend fun <R, T> send(build: () -> R, send: suspend (R) -> T): T {
        val request = session.commit(ticket) { gate.commit(gateTicket) { build() } }
        return send(request)
    }
}

/** Gateway-hosted Group Chats (Hermes Desktop's Bot Mode rooms), shared with every surface of the gateway. */
object Rooms {
    val list = MutableStateFlow<Map<String, Room>>(emptyMap())
    val events = MutableStateFlow<Map<String, List<RoomEvent>>>(emptyMap())
    /** Older-page cursor per room: null once the start of the log is loaded; absent before the first page. */
    val before = MutableStateFlow<Map<String, Long?>>(emptyMap())
    /** The raw log seq each room's history is complete through (projected events skip driver bookkeeping). */
    private val through = HashMap<String, Long>()
    val drivers = MutableStateFlow<Map<String, RoomDriver>>(emptyMap())
    val bots = MutableStateFlow<List<RoomBot>>(emptyList())
    /** False when this gateway has no hosted rooms, or its plugin predates them (no /v1/rooms route). */
    val supported = MutableStateFlow(true)
    val driverUp = MutableStateFlow(true)
    val loaded = MutableStateFlow(false)
    @Volatile var openRoom: String? = null

    private fun enc(s: String) = Uri.encode(s)

    private suspend fun <T> call(fetch: suspend () -> T, publish: (T) -> Unit): T? =
        loadCapability({ Hub.features.value.rooms }, Api.epoch, Hub.epochFor("/v1/rooms")!!, fetch, publish)

    fun visible(): Boolean = Hub.features.value.rooms && supported.value

    fun put(r: Room) = list.update { it + (r.id to r) }

    suspend fun load() {
        call({ roomListAnswer { Api.get("/v1/rooms") } }) { o ->
            supported.value = o.optBoolean("supported", true)
            driverUp.value = o.optBoolean("driver", true)
            val before = list.value
            list.value = o.optJSONArray("rooms").objs().map(RoomLogic::room).associateBy { it.id }
            if (Hub.isAppInitialized()) {
                for (r in list.value.values) approvals(Hub.app, before[r.id], r)
                for ((id, gone) in before) if (id !in list.value) approvals(Hub.app, gone, null)
            }
            bots.value = o.optJSONArray("bots").objs().map { RoomBot(it.str("profile"), it.str("name").ifBlank { it.str("profile") }) }
            loaded.value = true
        }
    }

    /** Room detail (driver state and pending actions) plus whatever history this phone has not seen. */
    suspend fun refresh(rid: String) {
        call({ Api.get("/v1/rooms/${enc(rid)}") }) { d ->
            d.optJSONObject("room")?.let { put(RoomLogic.room(it)) }
            drivers.update { it + (rid to RoomLogic.driver(d.optJSONObject("driver"))) }
        }
        catchUp(rid)
    }

    suspend fun catchUp(rid: String) {
        val from = synchronized(through) { through[rid] }
        if (from == null) {
            call({ Api.get("/v1/rooms/${enc(rid)}/events?limit=80") }) { page -> absorb(rid, page, older = false) }
            // The newest page can be driver bookkeeping only: walk back a few pages for something to show.
            repeat(5) { if (events.value[rid].isNullOrEmpty() && !older(rid)) return }
            return
        }
        var after: Long = from
        repeat(20) {
            val page = call({ Api.get("/v1/rooms/${enc(rid)}/events?after=$after&limit=200") }) { absorb(rid, it, older = false) } ?: return
            val next = page.optLong("to_seq", after)
            if (!page.optBoolean("has_more_after") || next <= after) return
            after = next
        }
    }

    suspend fun older(rid: String): Boolean {
        val b = before.value[rid] ?: return false
        call({ Api.get("/v1/rooms/${enc(rid)}/events?before=$b&limit=80") }) { absorb(rid, it, older = true) }
        return true
    }

    private fun absorb(rid: String, page: JSONObject, older: Boolean) {
        val evs = page.optJSONArray("events").objs().map(RoomLogic::event)
        events.update { m -> m + (rid to RoomLogic.merge(m[rid].orEmpty(), evs)) }
        if (older || rid !in before.value) {
            before.update { it + (rid to if (page.isNull("before") || !page.has("before")) null else page.optLong("before")) }
        }
        if (!older) synchronized(through) { through[rid] = maxOf(through[rid] ?: 0L, page.optLong("to_seq")) }
    }

    suspend fun send(rid: String, text: String, atts: List<JSONObject>, clientId: String): RoomEvent? {
        val body = JSONObject().put("text", text).put("client_id", clientId)
        if (atts.isNotEmpty()) body.put("attachments", JSONArray(atts))
        var sent: RoomEvent? = null
        call({ Api.post("/v1/rooms/${enc(rid)}/messages", body) }) { o ->
            o.optJSONObject("event")?.let { e ->
                val ev = RoomLogic.event(e).also { sent = it }
                events.update { m -> m + (rid to RoomLogic.merge(m[rid].orEmpty(), listOf(ev))) }
            }
        }
        return sent
    }

    suspend fun create(name: String, profiles: List<String>): Room? {
        var made: Room? = null
        val body = JSONObject().put("name", name).put("members", JSONArray(profiles)).put("room_id", java.util.UUID.randomUUID().toString())
        call({ Api.post("/v1/rooms", body) }) { o -> o.optJSONObject("room")?.let { made = RoomLogic.room(it).also(::put) } }
        return made
    }

    suspend fun rename(rid: String, name: String) {
        call({ Api.patch("/v1/rooms/${enc(rid)}", JSONObject().put("name", name)) }) { o -> o.optJSONObject("room")?.let { put(RoomLogic.room(it)) } }
    }

    suspend fun delete(rid: String) {
        call({ Api.delete("/v1/rooms/${enc(rid)}") }) { onRemoved(rid) }
    }

    suspend fun stop(rid: String) { call({ Api.post("/v1/rooms/${enc(rid)}/stop") }) { } }

    /** Clears the badge at once; the cursor post runs on the app scope, so the recomposition this causes
     *  (the effect that called it is keyed on the room) cannot cancel it before the gateway saves it. The post
     *  is bound to the machine and capability generation of this call: queued behind a machine switch, it is
     *  never built with the new machine's credentials (two gateways can share a room id). */
    fun markRead(rid: String, seq: Long) {
        val r = list.value[rid] ?: return
        if (seq <= r.readSeq && r.unread == 0) return
        val gate = Hub.epochFor("/v1/rooms")!!
        val bound = MachineBound(Api.epoch, Api.epoch.capture(), gate, gate.capture())
        put(r.copy(readSeq = maxOf(seq, r.readSeq), unread = 0))
        Notify.cancelRoom(Hub.app, rid)
        val path = "/v1/rooms/${enc(rid)}/read"
        val body = JSONObject().put("seq", seq)
        Hub.scope.launch {
            try { bound.send({ Api.buildPost(path, body) }) { Api.send(it) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    suspend fun act(rid: String, a: RoomAction, choice: String = "", headers: Map<String, String> = emptyMap()) {
        val body = JSONObject().put("kind", a.kind).put("member", a.member).put("task_id", a.taskId)
            .put("generation", a.generation).put("request_id", a.requestId)
        if (choice.isNotBlank()) body.put("choice", choice)
        call({ Api.post("/v1/rooms/${enc(rid)}/actions", body, headers) }) { }
    }

    /** SSE `room`: the plugin's poll saw this room change. */
    fun onRoom(c: Context, o: JSONObject) {
        val next = RoomLogic.room(o)
        if (next.id.isBlank()) return
        val before = list.value[next.id]
        put(next)
        if (RoomLogic.notifies(before, next, Hub.foreground)) Notify.room(c, next)
        approvals(c, before, next)
    }

    /** A member's new command approval asks like a chat approval; an answered one stops asking. */
    private fun approvals(c: Context, before: Room?, after: Room?) {
        if (after != null && RoomLogic.asksOutside(after.id, Hub.foreground, openRoom))
            for (a in RoomLogic.newApprovals(before, after)) Notify.roomApproval(c, after, a)
        for (a in RoomLogic.answeredApprovals(before, after)) Notify.cancelRoomApproval(c, before?.id ?: return, a)
    }

    fun onRemoved(rid: String) {
        val gone = list.value[rid]
        if (gone != null && Hub.isAppInitialized()) approvals(Hub.app, gone, null)
        list.update { it - rid }
        events.update { it - rid }
        before.update { it - rid }
        drivers.update { it - rid }
        synchronized(through) { through.remove(rid) }
        if (Hub.isAppInitialized()) Notify.cancelRoom(Hub.app, rid)
    }

    fun reset() {
        list.value = emptyMap(); events.value = emptyMap(); before.value = emptyMap(); drivers.value = emptyMap()
        synchronized(through) { through.clear() }
        bots.value = emptyList(); supported.value = true; driverUp.value = true; loaded.value = false; openRoom = null
    }
}
