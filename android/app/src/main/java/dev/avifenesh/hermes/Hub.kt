package dev.avifenesh.hermes

import android.content.Context
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject

data class Chat(val id: String, val title: String, val kind: String, val updated: Double, val unread: Int,
                val pinned: Boolean, val archived: Boolean, val preview: String, val lastRole: String, val profile: String = "")
data class Msg(val id: String, val chatId: String, val role: String, val kind: String, val content: String,
               val meta: JSONObject, val ts: Double, val seq: Long)
data class Act(val label: String, val choice: String, val style: String)
data class Appr(val id: String, val chatId: String, val command: String, val description: String,
                val actions: List<Act>, val state: String, val choice: String, val ts: Double)

/** Optional server surfaces from /v1/me `features`. All-true default: pre-fetch (no flicker) and old servers without the key (back-compat). */
data class Features(val radar: Boolean = true, val fleet: Boolean = true, val wakeWa: Boolean = true, val projects: Boolean = true) {
    fun allows(path: String): Boolean = when (feature(path)) {
        "radar" -> radar
        "fleet" -> fleet
        "projects" -> projects
        "wake_wa" -> wakeWa
        else -> true
    }

    companion object {
        fun feature(path: String): String? {
            val p = path.substringBefore('?')
            fun under(root: String) = p == root || p.startsWith("$root/")
            return when {
                under("/v1/radar") -> "radar"
                under("/v1/fleet") -> "fleet"
                under("/v1/projects") -> "projects"
                p == "/v1/wake/whatsapp-test" -> "wake_wa"
                else -> null
            }
        }
        fun from(flags: Map<String, Boolean?> = emptyMap()) = Features(
            flags["radar"] ?: true, flags["fleet"] ?: true,
            flags["wake_wa"] ?: true, flags["projects"] ?: true)
    }
}

fun nowS() = System.currentTimeMillis() / 1000.0

@androidx.compose.runtime.Composable
fun agentName(): String = Hub.agent.collectAsState().value

object Hub {
    val chats = MutableStateFlow<Map<String, Chat>>(emptyMap())
    val msgs = MutableStateFlow<Map<String, List<Msg>>>(emptyMap())
    val approvals = MutableStateFlow<Map<String, Appr>>(emptyMap())
    val working = MutableStateFlow<Map<String, Double>>(emptyMap())
    val online = MutableStateFlow(false)
    private val metadata = MutableStateFlow(JSONObject())
    val meta: StateFlow<JSONObject> = metadata.asStateFlow()
    private val capabilities = MutableStateFlow(Features())
    val features: StateFlow<Features> = capabilities.asStateFlow()
    internal val capabilityEpoch = SessionEpoch()
    private val otherCapabilityEpochs = mapOf("radar" to SessionEpoch(), "projects" to SessionEpoch(), "wake_wa" to SessionEpoch())
    internal fun epochFor(path: String): SessionEpoch? = when (val key = Features.feature(path)) {
        "fleet" -> capabilityEpoch
        else -> otherCapabilityEpochs[key]
    }
    val cmds = MutableStateFlow<List<JSONObject>>(emptyList())
    /** chat -> {"turn": latest turn usage, "session": session totals} */
    val usage = MutableStateFlow<Map<String, JSONObject>>(emptyMap())
    /** The agent's display name from /v1/me (agent_name), persisted so cold starts, notifications and widgets use the last known one. */
    val agent = MutableStateFlow(Prefs.agentName)
    /** /v1/health `degraded`: endpoints failing on the server ({path, status, error}). */
    val degraded = MutableStateFlow<List<JSONObject>>(emptyList())
    @Volatile var openChat: String? = null
    @Volatile var foreground = false
    lateinit var app: Context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mx = Mutex()
    private val meMx = Mutex()
    private var es: EventSource? = null
    private var retry: Job? = null
    @Volatile private var wanted = false
    private var backoff = 1000L

    fun parseFeatures(m: JSONObject): Features {
        val f = m.optJSONObject("features") ?: return Features()
        return Features.from(listOf("radar", "fleet", "wake_wa", "projects")
            .associateWith { if (f.has(it) && !f.isNull(it)) f.optBoolean(it, true) else null })
    }

    /** One synchronous publication path: a delayed collector must never restore older gates. */
    private fun publishMe(o: JSONObject) {
        val next = parseFeatures(o)
        val old = capabilities.value
        // Unrelated gate changes must not terminate a still-enabled Fleet polling coroutine.
        if (next.fleet != old.fleet) capabilityEpoch.invalidate()
        if (next.radar != old.radar) otherCapabilityEpochs.getValue("radar").invalidate()
        if (next.projects != old.projects) otherCapabilityEpochs.getValue("projects").invalidate()
        if (next.wakeWa != old.wakeWa) otherCapabilityEpochs.getValue("wake_wa").invalidate()
        capabilities.value = next
        metadata.value = o
        if (!next.fleet) Fleet.reset()
        o.str("agent_name").trim().takeIf { it.isNotBlank() }?.let { agent.value = it; Prefs.agentName = it }
        if (::app.isInitialized) runCatching { Widget.update(app) }
    }

    suspend fun refreshMe(): Unit = meMx.withLock { sessionRequest({ Api.get("/v1/me") }) { publishMe(it) } }

    /** Reset and response publication share a lock, so an old machine cannot repopulate state. */
    private suspend fun <T, R> sessionRequest(fetch: suspend () -> T, publish: (T) -> R): R {
        val ticket = Api.epoch.capture()
        val result = fetch()
        currentCoroutineContext().ensureActive()
        return Api.epoch.commit(ticket) { publish(result) }
    }

    // ---- parsing
    fun chat(o: JSONObject) = Chat(o.getString("id"), o.str("title").ifBlank { "Chat" }, o.str("kind", "chat"), o.optDouble("updated", 0.0),
        o.optInt("unread"), o.optInt("pinned") == 1, o.optInt("archived") == 1, o.str("preview"), o.str("last_role"), o.str("profile"))
    fun msg(o: JSONObject) = Msg(o.getString("id"), o.getString("chat_id"), o.str("role"), o.str("kind", "text"), o.str("content"),
        o.optJSONObject("meta") ?: JSONObject(), o.optDouble("ts", 0.0), o.optLong("seq"))
    fun appr(o: JSONObject): Appr {
        val acts = ArrayList<Act>()
        val a = o.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until a.length()) {
            val x = a.opt(i)
            when (x) {
                is JSONArray -> if (x.length() >= 2) acts += Act(x.optString(0), x.optString(1), x.optString(2, ""))
                is JSONObject -> acts += Act(x.str("label"), x.str("choice", x.str("value")), x.str("style"))
            }
        }
        return Appr(o.getString("id"), o.str("chat_id"), o.str("command"), o.str("description"), acts,
            o.str("state", "pending"), o.str("choice"), o.optDouble("ts", 0.0))
    }

    // Gateway progress/housekeeping lines (mirrors store.is_notice on the server, so messages stored before
    // the server learned to tag them fold the same way).
    private val NOTICE_HEAD = Regex("^\\s*(?:\u23f3|\u267b\ufe0f?|\ud83e\udde0|\ud83d\udcbb|\ud83d\udc0d|\ud83d\udcda|\ud83d\udcd6|\ud83d\udc41\ufe0f?|\u270d\ufe0f?|\ud83d\udd00|\ud83c\udf10|\ud83d\udd0d|\ud83d\udcc4|\u23e9|\u26a1|\ud83e\uddf9|\ud83d\udddc\ufe0f?|\ud83d\udd04|\ud83d\udcbe|\ud83d\udcec|\u2705|\u274c)\\s")
    private val NOTICE_LINE = Regex("^\\s*(?:\ud83d\udcbb|\ud83d\udc0d|\ud83d\udcda|\ud83d\udcd6|\ud83d\udc41\ufe0f?|\u270d\ufe0f?|\ud83d\udd00|\ud83c\udf10|\ud83d\udd0d|\ud83d\udcc4)\\s")
    fun isNotice(m: Msg): Boolean {
        if (m.kind == "status") return true
        if (m.kind != "text" || m.role == "user" || m.content.length > 2000) return false
        val lines = m.content.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return false
        if (lines.size == 1) return NOTICE_HEAD.containsMatchIn(lines[0])
        val hits = lines.count { NOTICE_LINE.containsMatchIn(it) }
        return NOTICE_LINE.containsMatchIn(lines[0]) && hits >= maxOf(2, (lines.size + 1) / 2)
    }

    fun plain(s: String): String = s.replace(Regex("```[a-zA-Z]*\\n?"), "").replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "\$1")
        .replace(Regex("[*_`#>]"), "").replace(Regex("\\n{3,}"), "\n\n").trim()

    fun previewOf(m: Msg): String = when (m.kind) {
        "approval" -> "Needs approval: " + m.meta.str("command")
        "image" -> "Photo" + (if (m.content.isNotBlank()) " · " + m.content else "")
        "file" -> "File: " + m.meta.str("file_name")
        "voice" -> "Voice message"
        else -> plain(m.content).lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
    }

    // ---- state updates
    private fun upsertChat(c: Chat, hasPreview: Boolean) {
        chats.update { m ->
            val old = m[c.id]
            m + (c.id to if (!hasPreview && old != null) c.copy(preview = old.preview, lastRole = old.lastRole) else c)
        }
    }

    private fun upsertMsgs(list: List<Msg>) {
        if (list.isEmpty()) return
        msgs.update { m ->
            val out = m.toMutableMap()
            list.groupBy { it.chatId }.forEach { (cid, l) ->
                val cur = (out[cid] ?: emptyList()).associateBy { it.id }.toMutableMap()
                l.forEach { cur[it.id] = it }
                out[cid] = cur.values.sortedBy { it.ts }
            }
            out
        }
        list.filter { it.kind !in setOf("trace", "activity", "deleted") }.groupBy { it.chatId }.forEach { (cid, l) ->
            val last = l.maxBy { it.ts }
            chats.update { m -> m[cid]?.let { c -> if (last.ts >= c.updated - 2) m + (cid to c.copy(preview = previewOf(last), lastRole = last.role)) else m } ?: m }
        }
    }

    private fun setWorking(a: JSONArray) {
        working.value = (0 until a.length()).associate { val j = a.getJSONObject(it); j.getString("chat_id") to j.optDouble("since", nowS()) }
    }

    fun apply(o: JSONObject) {
        o.optJSONArray("chats")?.let { a -> for (i in 0 until a.length()) { val j = a.getJSONObject(i); upsertChat(chat(j), j.has("preview")) } }
        o.optJSONArray("messages")?.let { a -> upsertMsgs((0 until a.length()).map { msg(a.getJSONObject(it)) }) }
        o.optJSONArray("approvals")?.let { a -> val l = (0 until a.length()).map { appr(a.getJSONObject(it)) }; approvals.update { m -> m + l.associateBy { it.id } } }
        o.optJSONArray("working")?.let { setWorking(it) }
        val s = o.optLong("seq")
        if (s > Prefs.seq) Prefs.seq = s
        if (foreground && Prefs.seq > Prefs.nseq) Prefs.nseq = Prefs.seq
    }

    suspend fun sync(full: Boolean = false): JSONObject = mx.withLock {
        // State is in memory only, so a fresh process (killed in background, app update) must pull everything once;
        // a delta from Prefs.seq would leave the inbox holding only chats that changed since.
        sessionRequest({ Api.get("/v1/sync?since=${if (full || chats.value.isEmpty()) 0 else Prefs.seq}") }) {
            apply(it); it
        }
    }

    /** After a sync in the background: notify for anything new, and for pending approvals. */
    fun notifyPending(c: Context) {
        msgs.value.values.flatten().filter { it.seq > Prefs.nseq }.sortedBy { it.seq }.forEach { Notify.consider(c, it) }
        approvals.value.values.forEach { Notify.approval(c, it) }
    }

    // ---- live connection (SSE)
    fun connect(): Unit = synchronized(Api.epoch) {
        if (!Prefs.paired) return@synchronized
        wanted = true
        if (es != null) return@synchronized
        retry?.cancel(); retry = null
        val req = Api.request("/v1/events").header("Accept", "text/event-stream").build()
        es = EventSources.createFactory(Api.sse).newEventSource(req, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) = synchronized(Api.epoch) {
                if (es === eventSource && wanted) { online.value = true; backoff = 1000 }
            }
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) = synchronized(Api.epoch) {
                if (es === eventSource && wanted) runCatching { handle(JSONObject(data)) }
                Unit
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) { dropped(eventSource) }
            override fun onClosed(eventSource: EventSource) { dropped(eventSource) }
        })
    }

    private fun dropped(src: EventSource): Unit = synchronized(Api.epoch) {
        // A cancelled old stream must not mark its replacement offline or start a second retry.
        if (es !== src) return@synchronized
        es = null
        online.value = false
        if (!wanted || !(foreground || Prefs.live || Prefs.phoneArmed)) return@synchronized
        retry?.cancel()
        val ticket = Api.epoch.capture()
        retry = scope.launch {
            delay(backoff)
            Api.epoch.commit(ticket) {
                if (wanted) { backoff = (backoff * 2).coerceAtMost(30_000); connect() }
            }
        }
    }

    fun disconnect(): Unit = synchronized(Api.epoch) {
        wanted = false
        retry?.cancel(); retry = null
        val old = es; es = null; old?.cancel()
        online.value = false; backoff = 1000
    }

    private fun handle(e: JSONObject) {
        when (e.optString("type")) {
            "phone" -> PhoneService.pull()
            "hello", "seq" -> {
                if (Prefs.phoneArmed) PhoneService.pull()
                e.optJSONArray("working")?.let { setWorking(it) }
                if (e.optLong("seq") > Prefs.seq) scope.launch { runCatching { sync(); if (!foreground) notifyPending(app) } }
            }
            "message" -> {
                val m = msg(e.getJSONObject("message"))
                e.optJSONObject("chat")?.let { upsertChat(chat(it), it.has("preview")) }
                upsertMsgs(listOf(m)); bump(e)
                Notify.consider(app, m)
            }
            "chat" -> { e.optJSONObject("chat")?.let { upsertChat(chat(it), it.has("preview")) }; bump(e) }
            "approval" -> {
                val a = appr(e.getJSONObject("approval"))
                approvals.update { it + (a.id to a) }; bump(e)
                Notify.approval(app, a)
            }
            "usage" -> {
                val cid = e.getString("chat_id")
                val turn = e.optJSONObject("usage") ?: return
                usage.update { m -> m + (cid to JSONObject((m[cid] ?: JSONObject()).toString()).put("turn", turn)) }
            }
            "fleet" -> if (features.value.fleet) {
                e.optJSONObject("event")?.let { Fleet.onEvent(app, it) }
                scope.launch { runCatching { Fleet.load() } }
            }
            "working" -> {
                val cid = e.getString("chat_id")
                working.update { if (e.optBoolean("working")) it + (cid to e.optDouble("since", nowS())) else it - cid }
            }
        }
    }

    private fun bump(e: JSONObject) {
        val s = e.optLong("seq")
        if (s > Prefs.seq) Prefs.seq = s
        if (foreground && Prefs.seq > Prefs.nseq) Prefs.nseq = Prefs.seq
    }

    // ---- actions
    suspend fun afterPair(c: Context, ticket: Long = Api.epoch.capture()) {
        Api.epoch.commit(ticket) { Prefs.seq = 0; Wake.activate(ticket) }
        // Reset discarded the previous machine's alarms. Re-arm only from this pairing's response.
        try { Wake.refresh(c.applicationContext, ticket) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        // Pairing is already committed. Offline bootstrap must not roll back valid credentials.
        try { sync(full = true); Api.epoch.commit(ticket) { Prefs.nseq = Prefs.seq } } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        Api.epoch.commit(ticket) { }
        try { refreshMe() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        Api.epoch.commit(ticket) { }
        val push = try { Push.refresh(c) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        Api.epoch.commit(ticket) { Prefs.live = !push; connect() }
        LiveService.sync(c)
    }

    /** Slash-command catalog for the composer popover; also refreshes model/profile meta. */
    suspend fun loadCmds(force: Boolean = false) {
        if (!force && cmds.value.isNotEmpty()) return
        sessionRequest({ Api.get("/v1/commands") }) { o ->
            cmds.value = o.optJSONArray("commands").objs()
            val m = JSONObject(meta.value.toString())
            // Commands responses are not capability advertisements.
            o.keys().forEach { k -> if (k != "commands" && k != "features") m.put(k, o.get(k)) }
            metadata.value = m
        }
    }

    suspend fun health(): JSONObject = sessionRequest({ Api.get("/v1/health") }) {
        degraded.value = it.optJSONArray("degraded").objs(); it
    }

    suspend fun loadUsage(cid: String) {
        sessionRequest({ Api.get("/v1/chats/$cid/usage") }) { o -> usage.update { it + (cid to o) } }
    }

    suspend fun loadChat(cid: String) {
        sessionRequest({ Api.get("/v1/chats/$cid/messages?limit=150") }) { o ->
            val a = o.getJSONArray("messages")
            upsertMsgs((0 until a.length()).map { msg(a.getJSONObject(it)) })
        }
    }

    suspend fun markRead(cid: String) {
        chats.update { m -> m[cid]?.let { m + (cid to it.copy(unread = 0)) } ?: m }
        Notify.cancelChat(app, cid)
        try { Api.post("/v1/chats/$cid/read") } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    suspend fun send(cid: String, text: String, mode: String = "send", atts: List<JSONObject> = emptyList()): Msg {
        val b = JSONObject().put("text", text).put("mode", mode)
        if (atts.isNotEmpty()) b.put("attachments", JSONArray(atts))
        return sessionRequest({ Api.post("/v1/chats/$cid/messages", b) }) { o ->
            msg(o.getJSONObject("message")).also { upsertMsgs(listOf(it)) }
        }
    }

    suspend fun newChat(title: String, profile: String = "default"): String {
        return sessionRequest({ Api.post("/v1/chats", JSONObject().put("title", title).put("profile", profile)) }) { o ->
            val c = chat(o.getJSONObject("chat")); upsertChat(c, false); c.id
        }
    }

    suspend fun patch(cid: String, k: String, v: Any) {
        sessionRequest({ Api.patch("/v1/chats/$cid", JSONObject().put(k, v)) }) { o ->
            o.optJSONObject("chat")?.let { upsertChat(chat(it), false) }
        }
    }

    suspend fun stop(cid: String) { Api.post("/v1/chats/$cid/stop") }

    suspend fun resolve(aid: String, choice: String, ts: String?, asig: String?): Appr? {
        val h = HashMap<String, String>()
        if (ts != null) h["X-HM-Ts"] = ts
        if (asig != null) h["X-HM-ASig"] = asig
        return sessionRequest({ Api.post("/v1/approvals/$aid", JSONObject().put("choice", choice), h) }) { o ->
            val a = o.optJSONObject("approval")?.let { appr(it) }
            if (a != null) approvals.update { it + (a.id to a) }
            a
        }
    }

    fun reset() {
        synchronized(Api.epoch) {
            Api.epoch.invalidate()
            if (::app.isInitialized) Wake.reset(app.applicationContext)
            Api.http.dispatcher.cancelAll()
            disconnect()
            capabilityEpoch.invalidate()
            otherCapabilityEpochs.values.forEach { it.invalidate() }
            chats.value = emptyMap(); msgs.value = emptyMap(); approvals.value = emptyMap(); working.value = emptyMap()
            metadata.value = JSONObject(); capabilities.value = Features()
            cmds.value = emptyList(); usage.value = emptyMap(); degraded.value = emptyList()
            openChat = null; agent.value = "Hermes"; Prefs.agentName = "Hermes"
            Prefs.seq = 0; Prefs.nseq = 0; Prefs.fleetSeen = 0.0
            Prefs.talkChat = null; Prefs.lastProfile = null
            Fleet.reset()
            if (::app.isInitialized) runCatching { androidx.core.app.NotificationManagerCompat.from(app).cancelAll() }
        }
    }
}
