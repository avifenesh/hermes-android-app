package dev.avifenesh.hermes

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Disabling or switching while a fetch is in flight must discard its result, including off/on cycles. */
internal suspend fun <T> loadCapability(
    enabled: () -> Boolean, session: SessionEpoch, capability: SessionEpoch,
    fetch: suspend () -> T, publish: (T) -> Unit,
): T? {
    val ticket: Long
    val gate: Long
    synchronized(session) {
        if (!enabled()) return null
        ticket = session.capture(); gate = capability.capture()
    }
    val result = fetch()
    currentCoroutineContext().ensureActive()
    return session.commit(ticket) {
        capability.commit(gate) {
            if (enabled()) result.also(publish) else null
        }
    }
}

/** Every coding agent on the machine (claude-ctrl) plus Hermes' own runs. */
object Fleet {
    val snap = MutableStateFlow<JSONObject?>(null)
    val err = MutableStateFlow<String?>(null)

    suspend fun load(recent: Boolean = false): JSONObject {
        return loadCapability({ Hub.features.value.fleet }, Api.epoch, Hub.capabilityEpoch,
            { Api.get("/v1/fleet" + if (recent) "?recent=1" else "") }) { o ->
            snap.value = o; err.value = o.str("error").ifBlank { null }
            runCatching { Widget.update(Hub.app) }
        } ?: JSONObject()
    }

    fun reset() { snap.value = null; err.value = null }
    fun agents(): List<JSONObject> = if (Hub.features.value.fleet) snap.value?.optJSONArray("agents").objs() else emptyList()
    fun count(g: String): Int = if (Hub.features.value.fleet) snap.value?.optJSONObject("counts")?.optInt(g) ?: 0 else 0

    suspend fun send(id: String, text: String, headers: Map<String, String> = emptyMap()): JSONObject =
        Api.post("/v1/fleet/${enc(id)}/send", JSONObject().put("text", text), headers)

    /** Sessions with permission prompts off need a biometric signature bound to the exact text. */
    suspend fun sendGuarded(act: androidx.fragment.app.FragmentActivity, id: String, name: String, text: String): JSONObject {
        val ticket = Api.epoch.capture()
        val gate = Hub.capabilityEpoch.capture()
        try { return send(id, text) } catch (e: ApiError) {
            if (e.code != 403) throw e
        }
        val h = fleetSignature(act, id, name, text) ?: throw ApiError(0, "Cancelled")
        Api.epoch.commit(ticket) { Hub.capabilityEpoch.commit(gate) { } }
        return send(id, text, h)
    }
    suspend fun stop(id: String): JSONObject = Api.post("/v1/fleet/${enc(id)}/stop")
    suspend fun tail(id: String, n: Int = 80): JSONObject = Api.get("/v1/fleet/${enc(id)}/tail?n=$n")
    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Background: notify for agents that became "waiting on you" since we last looked. */
    suspend fun notifyNew(c: Context) {
        if (!Hub.features.value.fleet) return
        val ticket = Api.epoch.capture()
        val gate = Hub.capabilityEpoch.capture()
        val o = try { load() } catch (e: CancellationException) { throw e } catch (_: Exception) { return }
        Api.epoch.commit(ticket) {
            Hub.capabilityEpoch.commit(gate) gateCommit@{
                if (!Hub.features.value.fleet) return@gateCommit
                val evs = o.optJSONArray("events").objs().filter { it.optDouble("at") > Prefs.fleetSeen && it.optDouble("at") > nowS() - 900 }
                if (evs.isNotEmpty()) {
                    Prefs.fleetSeen = evs.maxOf { it.optDouble("at") }
                    val byId = agents().associateBy { it.str("id") }
                    evs.groupBy { it.str("id") }.forEach { (id, l) -> waiting(c, l.maxBy { it.optDouble("at") }, byId[id]) }
                }
            }
        }
    }

    fun onEvent(c: Context, ev: JSONObject) = synchronized(Api.epoch) {
        if (!Hub.features.value.fleet || ev.optDouble("at") <= Prefs.fleetSeen) return@synchronized
        Prefs.fleetSeen = ev.optDouble("at")
        if (Hub.foreground) { Ui.say("${ev.str("name").ifBlank { ev.str("tool") }} is waiting on you"); return@synchronized }
        waiting(c, ev, agents().firstOrNull { it.str("id") == ev.str("id") })
    }

    private fun waiting(c: Context, ev: JSONObject, a: JSONObject?) {
        if (!Hub.features.value.fleet) return
        val id = ev.str("id")
        val name = ev.str("name").ifBlank { a?.str("name") ?: "" }.ifBlank { ev.str("tool").ifBlank { "Agent" } }
        val pi = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(c, ("fo$id").hashCode(),
            Intent(c, MainActivity::class.java).putExtra("agent", id).putExtra("agent_name", name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), pi)
        val b = NotificationCompat.Builder(c, Notify.CH_APPR).setSmallIcon(R.drawable.ic_stat).setColor(0xFFFF8A66.toInt())
            .setContentTitle("$name needs you").setContentText(ev.str("status"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(ev.str("status"), a?.str("project")?.let { if (it.isBlank()) "" else "· $it" } ?: "")
                .filter { it.isNotBlank() }.joinToString("\n")))
            .setSubText(ev.str("tool")).setCategory(NotificationCompat.CATEGORY_MESSAGE).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(open)
        if ((a == null || a.optBoolean("can_reply", true)) && a?.optBoolean("unguarded") != true) {
            val reply = RemoteInput.Builder("reply").setLabel("Reply to $name").build()
            val rpi = PendingIntent.getBroadcast(c, ("fr$id").hashCode(),
                Intent(c, ActionReceiver::class.java).setAction("fleet_reply").putExtra("base", Prefs.base).putExtra("agent", id).putExtra("name", name),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            b.addAction(NotificationCompat.Action.Builder(0, "Reply", rpi).addRemoteInput(reply)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).setShowsUserInterface(false).build())
            for ((label, text) in listOf("Go ahead" to "go ahead", "Continue" to "continue")) {
                val qpi = PendingIntent.getBroadcast(c, ("fq$id$label").hashCode(),
                    Intent(c, ActionReceiver::class.java).setAction("fleet_reply").putExtra("base", Prefs.base).putExtra("agent", id).putExtra("name", name).putExtra("text", text), pi)
                b.addAction(0, label, qpi)
            }
        }
        Notify.post(c, ("f$id").hashCode(), b.build())
    }

    fun cancel(c: Context, id: String) = NotificationManagerCompat.from(c).cancel(("f$id").hashCode())
}

suspend fun fleetSignature(act: androidx.fragment.app.FragmentActivity, id: String, name: String, text: String): Map<String, String>? =
    kotlinx.coroutines.suspendCancellableCoroutine { k ->
        val ts = (System.currentTimeMillis() / 1000).toString()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        val payload = "fleet\n$id\n$digest\n$ts".toByteArray()
        val sig = runCatching { Keys.approvalSignature() }.getOrNull() ?: run { k.resume(null) {}; return@suspendCancellableCoroutine }
        fun done(s: java.security.Signature?) {
            if (!k.isActive) return
            val out = s?.let { runCatching { it.update(payload); android.util.Base64.encodeToString(it.sign(), android.util.Base64.NO_WRAP) }.getOrNull() }
            k.resume(out?.let { mapOf("X-HM-Ts" to ts, "X-HM-ASig" to it) }) {}
        }
        if (Prefs.noLock) { done(sig); return@suspendCancellableCoroutine }
        val prompt = androidx.biometric.BiometricPrompt(act, androidx.core.content.ContextCompat.getMainExecutor(act), object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: androidx.biometric.BiometricPrompt.AuthenticationResult) = done(r.cryptoObject?.signature)
            override fun onAuthenticationError(code: Int, s: CharSequence) { if (k.isActive) k.resume(null) {} }
        })
        k.invokeOnCancellation { prompt.cancelAuthentication() }
        prompt.authenticate(androidx.biometric.BiometricPrompt.PromptInfo.Builder().setTitle("Send to $name")
            .setSubtitle("Runs without permission prompts: \"" + text.take(80) + "\"")
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL).build(),
            androidx.biometric.BiometricPrompt.CryptoObject(sig))
    }

@Composable
fun stateColor(g: String): Color = when (g) { "waiting" -> P.att; "working" -> P.acct; "done" -> P.good; else -> P.mu }

@Composable
fun FleetSection() {
    val p = P
    val feat by Hub.features.collectAsState()
    if (!feat.fleet) return  // hidden + no 6s polling; Pulse's own Hermes-runs section below stays
    val snap by Fleet.snap.collectAsState()
    val err by Fleet.err.collectAsState()
    var showAll by remember { mutableStateOf(false) }
    LaunchedEffect(showAll) {
        while (true) {
            try { Fleet.load(showAll) } catch (e: CancellationException) { throw e } catch (e: Exception) { Fleet.err.value = e.message }
            delay(if (showAll) 20000 else 6000)
        }
    }
    val list = snap?.optJSONArray("agents").objs().filter { showAll || it.str("group") != "recent" }
    val waiting = list.count { it.str("group") == "waiting" }
    Section("Fleet", if (list.isEmpty()) "idle" else "$waiting waiting · ${list.count { it.str("group") == "working" }} working", if (waiting > 0) p.att else p.mu)
    // ctrl missing while feature is on (e.g. status file present, binary gone): muted note, not a red error.
    if (err != null) Text("Agent view: $err", style = sans(13f, 400, if (err!!.contains("not installed")) p.mu else p.bad), modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
    if (list.isEmpty() && snap != null) Text("No agents running.", style = sans(14f, 400, p.mu), modifier = Modifier.padding(horizontal = 20.dp))
    for (a in list) AgentRow(a)
    Text(if (showAll) "Hide ended sessions" else "Show ended sessions", style = sans(13.5f, 600, p.tx2),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).clickable { showAll = !showAll })
}

@Composable
fun AgentRow(a: JSONObject) {
    val p = P
    val g = a.str("group")
    Row(Modifier.fillMaxWidth().clickable { openAgent(a) }.padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        if (g == "working") LiveDot(9.dp, p.acct) else Dot(stateColor(g), 9.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.str("name").ifBlank { "Untitled" }, style = sans(15f, 600, p.tx), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Pill(a.str("kind") + (a.str("via").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), p.tx2, p.s2)
            }
            val d = a.str("detail")
            if (d.isNotBlank()) Text(d, style = sans(13f, 400, if (g == "waiting") p.att else p.mu), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(a.str("project"), a.str("where"), a.str("model")).filter { it.isNotBlank() && it != "tty" }.joinToString(" · "),
                style = mono(11f, 500, p.dim), maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Text(rel(a.optDouble("last", 0.0)), style = mono(11.5f, 500, p.mu))
    }
}

fun openAgent(a: JSONObject) {
    val id = a.str("id")
    when {
        id.startsWith("hm:") -> Ui.open(id.removePrefix("hm:"))
        id.startsWith("hs:") -> Ui.push(Route.SessR(id.removePrefix("hs:"), a.str("name"), a.str("source")))
        else -> Ui.push(Route.AgentR(id, a.str("name")))
    }
}

@Composable
fun AgentScreen(id: String, name: String) {
    val p = P
    val scope = rememberCoroutineScope()
    val act = LocalAct.current
    val snap by Fleet.snap.collectAsState()
    val feat by Hub.features.collectAsState()
    val a = snap?.optJSONArray("agents").objs().firstOrNull { it.str("id") == id }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(id, feat.fleet) { if (feat.fleet) { Fleet.cancel(Hub.app, id); runCatching { Fleet.load(true) } } }
    LaunchedEffect(id, feat.fleet) {
        lines = emptyList(); err = null
        while (feat.fleet) {
            runCatching { Fleet.tail(id, 80) }.onSuccess { o ->
                if (o.has("error")) err = o.str("error") else { err = null; lines = o.optJSONArray("lines").strs() }
            }.onFailure { if (it is CancellationException) throw it; err = it.message }
            delay(3000)
        }
    }
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.size) }
    fun send(t: String) {
        if (t.isBlank() || busy) return
        busy = true
        scope.launch {
            try {
                runCatching { Fleet.sendGuarded(act, id, a?.str("name") ?: name, t) }
                    .onSuccess { o -> Ui.say(o.str("line").ifBlank { "Sent" }); text = "" }
                    .onFailure { if (it is CancellationException) throw it; if (it.message != "Cancelled") Ui.say("Not sent: " + it.message) }
            } finally { busy = false }
        }
    }
    if (!feat.fleet) {  // e.g. tapped through from the static widget/shortcut with the feature off
        Column(Modifier.fillMaxSize()) {
            TopBar(name, null)
            Text("Fleet isn't available on this machine.", style = sans(14f, 400, p.mu), modifier = Modifier.padding(20.dp))
        }
        return
    }
    val g = a?.str("group") ?: ""
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(a?.str("name")?.ifBlank { null } ?: name, listOf(g, a?.str("kind") ?: "", a?.str("project") ?: "").filter { it.isNotBlank() }.joinToString(" · "), stateColor(g)) {
            if (a?.optBoolean("can_stop") == true) {
                Row(Modifier.padding(end = 8.dp).clip(RoundedCornerShape(12.dp)).background(p.badd).clickable {
                    scope.launch { runCatching { Fleet.stop(id) }.onSuccess { Ui.say("Sent Esc") }.onFailure { Ui.say(it.message) } }
                }.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icn(Ic.stop, p.bad, 12.dp); Spacer(Modifier.width(6.dp)); Text("Esc", style = sans(13.5f, 600, p.bad))
                }
            }
        }
        if (a?.optBoolean("unguarded") == true) Text("Permission prompts are off in this session: replies need your fingerprint.",
            style = sans(12.5f, 600, p.att), modifier = Modifier.fillMaxWidth().background(p.attd).padding(horizontal = 18.dp, vertical = 8.dp))
        val d = a?.str("detail") ?: ""
        if (d.isNotBlank()) Text(d, style = sans(14f, 500, if (g == "waiting") p.att else p.tx2), modifier = Modifier.fillMaxWidth().background(p.s1).padding(horizontal = 18.dp, vertical = 10.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().background(p.s1), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            if (err != null) item { Text(err!!, style = sans(13.5f, 500, p.bad)) }
            if (lines.isEmpty() && err == null) item { Text("Loading screen…", style = mono(12.5f, 500, p.mu)) }
            items(lines.size) { i -> Text(lines[i], style = mono(11.5f, 400, p.tx2), softWrap = true) }
        }
        Column(Modifier.fillMaxWidth().background(p.bg).navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (a?.optBoolean("can_reply", true) != false) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (q in listOf("go ahead", "continue", "yes", "no", "status?", "commit and open a PR")) Chip(q, false) { send(q) }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    InputBox(text, { text = it }, "Reply to ${a?.str("name")?.ifBlank { null } ?: "agent"}", Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    IconBtn(Ic.send, p.ink, 46.dp, p.acc, enabled = !busy && text.isNotBlank()) { send(text.trim()) }
                }
            } else Text(if (a?.str("via") == "desktop") "This session lives in the Claude desktop app; reply there." else "This terminal has no remote control; reply from there.", style = sans(13.5f, 400, p.mu), modifier = Modifier.padding(8.dp))
        }
    }
}
