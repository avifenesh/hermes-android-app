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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.withLock
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
        try { return checked(send(id, text)) } catch (e: ApiError) {
            if (e.code != 403) throw e
        }
        val h = fleetSignature(act, id, name, text) ?: throw CancellationException("Authentication cancelled")
        currentCoroutineContext().ensureActive()
        Api.epoch.commit(ticket) { Hub.capabilityEpoch.commit(gate) {
            if (!Hub.features.value.fleet) throw CancellationException("Fleet disabled")
        } }
        return checked(send(id, text, h))
    }

    /** Exact frozen JSONObject bytes are both signed and posted. Never sign display labels. */
    suspend fun inputGuarded(act: androidx.fragment.app.FragmentActivity, id: String, name: String, body: JSONObject): JSONObject {
        val ticket = Api.epoch.capture()
        val gate = Hub.capabilityEpoch.capture()
        val frozen = JSONObject(body.toString())
        val serialized = frozen.toString()
        val path = "/v1/fleet/${enc(id)}/input"
        try { return checked(Api.post(path, frozen)) } catch (e: ApiError) {
            // 401 is pairing/request authentication failure, not consent to weaken auth.
            if (e.code != 403) throw e
        }
        val h = fleetSignature(act, id, name, serialized, "fleet-input", str(R.string.fleet_control_title, name))
            ?: throw CancellationException("Authentication cancelled")
        currentCoroutineContext().ensureActive()
        Api.epoch.commit(ticket) { Hub.capabilityEpoch.commit(gate) {
            if (!Hub.features.value.fleet) throw CancellationException("Fleet disabled")
        } }
        return checked(Api.post(path, frozen, h))
    }

    fun checked(o: JSONObject): JSONObject {
        if (o.str("error").isNotBlank() || o.has("ok") && !o.optBoolean("ok"))
            throw ApiError(0, o.str("error").ifBlank { str(R.string.fleet_refused) })
        return o
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
        if (Hub.foreground) { Ui.say(R.string.fleet_waiting_on_you, ev.str("name").ifBlank { ev.str("tool") }); return@synchronized }
        waiting(c, ev, agents().firstOrNull { it.str("id") == ev.str("id") })
    }

    private fun waiting(c: Context, ev: JSONObject, a: JSONObject?) {
        if (!Hub.features.value.fleet) return
        val id = ev.str("id")
        val name = ev.str("name").ifBlank { a?.str("name") ?: "" }.ifBlank { ev.str("tool").ifBlank { str(R.string.fleet_agent_fallback) } }
        val pi = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(c, ("fo$id").hashCode(),
            Intent(c, MainActivity::class.java).putExtra("agent", id).putExtra("agent_name", name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), pi)
        val b = NotificationCompat.Builder(c, Notify.CH_APPR).setSmallIcon(R.drawable.ic_stat).setColor(0xFFFF8A66.toInt())
            .setContentTitle(str(R.string.fleet_needs_you, name)).setContentText(ev.str("status"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(ev.str("status"), a?.str("project")?.let { if (it.isBlank()) "" else "· $it" } ?: "")
                .filter { it.isNotBlank() }.joinToString("\n")))
            .setSubText(ev.str("tool")).setCategory(NotificationCompat.CATEGORY_MESSAGE).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(open)
        if ((a == null || a.optBoolean("can_reply", true)) && a?.optBoolean("unguarded") != true) {
            val reply = RemoteInput.Builder("reply").setLabel(str(R.string.fleet_reply_to, name)).build()
            val rpi = PendingIntent.getBroadcast(c, ("fr$id").hashCode(),
                Intent(c, ActionReceiver::class.java).setAction("fleet_reply").putExtra("base", Prefs.base).putExtra("agent", id).putExtra("name", name),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            b.addAction(NotificationCompat.Action.Builder(0, str(R.string.fleet_reply), rpi).addRemoteInput(reply)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).setShowsUserInterface(false).build())
            // The label is shown; the text is what the agent receives. Request codes key on the text, not the language.
            for ((label, text) in listOf(R.string.fleet_go_ahead to "go ahead", R.string.fleet_continue to "continue")) {
                val qpi = PendingIntent.getBroadcast(c, ("fq$id$text").hashCode(),
                    Intent(c, ActionReceiver::class.java).setAction("fleet_reply").putExtra("base", Prefs.base).putExtra("agent", id).putExtra("name", name).putExtra("text", text), pi)
                b.addAction(0, str(label), qpi)
            }
        }
        Notify.post(c, ("f$id").hashCode(), b.build())
    }

    fun cancel(c: Context, id: String) = NotificationManagerCompat.from(c).cancel(("f$id").hashCode())
}

suspend fun fleetSignature(act: androidx.fragment.app.FragmentActivity, id: String, name: String, text: String,
                           domain: String = "fleet", title: String = str(R.string.fleet_send_to_title, name)): Map<String, String>? =
    kotlinx.coroutines.suspendCancellableCoroutine { k ->
        val ts = (System.currentTimeMillis() / 1000).toString()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        val payload = "$domain\n$id\n$digest\n$ts".toByteArray(Charsets.UTF_8)
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
        prompt.authenticate(androidx.biometric.BiometricPrompt.PromptInfo.Builder().setTitle(title)
            .setSubtitle(if (domain == "fleet") str(R.string.fleet_sig_unguarded, text.take(80))
                else str(R.string.fleet_sig_input, text.take(90)))
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
    val visible = Ui.stack.isEmpty() && !Ui.composeOpen.value
    VisibleEffect(showAll, visible) {
        while (visible) {
            try { Fleet.load(showAll) } catch (e: CancellationException) { throw e } catch (e: Exception) { Fleet.err.value = e.message }
            delay(if (showAll) 20000 else 6000)
        }
    }
    val list = snap?.optJSONArray("agents").objs().filter { showAll || it.str("group") != "recent" }
    val waiting = list.count { it.str("group") == "waiting" }
    val working = list.count { it.str("group") == "working" }
    Section(stringResource(R.string.fleet_title), if (list.isEmpty()) stringResource(R.string.fleet_idle)
        else pluralStringResource(R.plurals.fleet_waiting_count, waiting, waiting) + " · " + pluralStringResource(R.plurals.fleet_working_count, working, working),
        if (waiting > 0) p.att else p.mu)
    // ctrl missing while feature is on (e.g. status file present, binary gone): muted note, not a red error.
    // "not installed" matches the gateway's own error text, which stays English.
    if (err != null) Text(stringResource(R.string.fleet_agent_view_error, err.orEmpty()), style = sans(13f, 400, if (err!!.contains("not installed")) p.mu else p.bad), modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
    if (list.isEmpty() && snap != null) Text(stringResource(R.string.fleet_no_agents), style = sans(14f, 400, p.mu), modifier = Modifier.padding(horizontal = 20.dp))
    for (a in list) AgentRow(a)
    Text(if (showAll) stringResource(R.string.fleet_hide_ended) else stringResource(R.string.fleet_show_ended), style = sans(13.5f, 600, p.tx2),
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
                Text(a.str("name").ifBlank { stringResource(R.string.fleet_untitled) }, style = sans(15f, 600, p.tx), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Pill(a.str("kind") + (a.str("via").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), p.tx2, p.s2)
            }
            val d = a.str("host_error").ifBlank { a.str("detail") }
            if (d.isNotBlank()) Text(d, style = sans(13f, 400, if (g == "waiting") p.att else p.mu), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(a.str("host"), a.str("project"), a.str("where"), a.str("model")).filter { it.isNotBlank() && it != "tty" }.joinToString(" · "),
                style = mono(11f, 500, p.dim), maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Text(rel(a.optDouble("last", 0.0)), style = mono(11.5f, 500, p.mu))
    }
}

/** The server's agent groups read in the app language; anything else shows as sent. */
private fun groupLabel(g: String): String = when (g) {
    "waiting" -> str(R.string.fleet_group_waiting)
    "working" -> str(R.string.fleet_group_working)
    "done" -> str(R.string.fleet_group_done)
    "recent" -> str(R.string.fleet_group_recent)
    else -> g
}

fun openAgent(a: JSONObject) {
    val id = a.str("id")
    when {
        id.startsWith("hm:") -> Ui.open(id.removePrefix("hm:"))
        id.startsWith("remote:") -> Ui.push(Route.AgentR(id, a.str("name")))
        id.startsWith("hs:") -> Ui.push(Route.SessR(id.removePrefix("hs:"), a.str("name"), a.str("source")))
        else -> Ui.push(Route.AgentR(id, a.str("name")))
    }
}

@Composable
fun AgentScreen(id: String, name: String) {
    val p = P
    val scope = rememberCoroutineScope()
    val act = LocalAct.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val imeVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    val snap by Fleet.snap.collectAsState()
    val feat by Hub.features.collectAsState()
    val a = snap?.optJSONArray("agents").objs().firstOrNull { it.str("id") == id }
    var screen by remember(id) { mutableStateOf<JSONObject?>(null) }
    var screenIdentity by remember(id) { mutableStateOf<Pair<Long, Long>?>(null) }
    val refreshLock = remember(id) { kotlinx.coroutines.sync.Mutex() }
    var err by remember(id) { mutableStateOf<String?>(null) }
    var feedback by remember(id) { mutableStateOf<String?>(null) }
    var text by rememberSaveable(id) { mutableStateOf("") }
    var busy by remember(id) { mutableStateOf(false) }
    var operation by remember(id) { mutableStateOf<Job?>(null) }
    val terminal = screen?.optJSONObject("terminal")?.takeIf { it.optBoolean("available") }
    val title = a?.str("name")?.ifBlank { null } ?: name
    fun check(ticket: Long, gate: Long) {
        Api.epoch.commit(ticket) { Hub.capabilityEpoch.commit(gate) {
            if (!Hub.features.value.fleet || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
                throw CancellationException("Screen is not visible")
        } }
    }
    suspend fun refresh() = refreshLock.withLock {
        val ticket = Api.epoch.capture()
        val gate = Hub.capabilityEpoch.capture()
        loadCapability({ Hub.features.value.fleet }, Api.epoch, Hub.capabilityEpoch,
            { Fleet.checked(Fleet.tail(id, 200)) }) {
            screen = it; screenIdentity = ticket to gate; err = null
        }
    }
    DisposableEffect(id, lifecycle, feat.fleet) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) operation?.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); operation?.cancel() }
    }
    VisibleEffect(id, feat.fleet) {
        if (feat.fleet) {
            Fleet.cancel(Hub.app, id)
            try { Fleet.load(true) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        while (feat.fleet) {
            try { refresh() } catch (e: CancellationException) { throw e } catch (e: Exception) { err = e.message }
            delay(3000)
        }
    }
    fun perform(draft: String? = null, input: JSONObject? = null, interrupt: Boolean = false) {
        if (busy || !feat.fleet || draft?.isBlank() == true) return
        val ticket = Api.epoch.capture()
        val gate = Hub.capabilityEpoch.capture()
        val origin = screenIdentity
        if (origin != null && (origin.first != ticket || origin.second != gate)) {
            feedback = str(R.string.agent_connection_changed)
            return
        }
        try { check(ticket, gate) } catch (_: CancellationException) { return }
        busy = true
        operation = scope.launch {
            try {
                check(ticket, gate)
                val response = if (interrupt) Fleet.stop(id)
                    else if (input != null) Fleet.inputGuarded(act, id, title, input)
                    else Fleet.sendGuarded(act, id, title, draft ?: "")
                currentCoroutineContext().ensureActive()
                check(ticket, gate)
                Fleet.checked(response)
                // Key presses and failed sends never clear a draft; edits during auth survive too.
                if (draft != null && text == draft) text = ""
                feedback = response.str("line").ifBlank {
                    when { interrupt -> str(R.string.agent_sent_esc); input == null -> str(R.string.agent_sent); else -> str(R.string.agent_input_sent) }
                }
                try { refresh() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    err = str(R.string.agent_refresh_failed, e.message)
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                feedback = str(R.string.agent_not_sent, e.message)
                try { check(ticket, gate); refresh() } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Preserve the action error and last readable screen. */ }
            }
            finally { busy = false }
        }
    }
    fun input(body: JSONObject) {
        val current = terminal ?: return
        if (body.str("action") == "scroll") body.put("frame", current.str("frame"))
        perform(input = body)
    }
    if (!feat.fleet) {
        Column(Modifier.fillMaxSize()) {
            TopBar(name, null)
            Text(stringResource(R.string.agent_fleet_unavailable), style = sans(14f, 400, p.mu), modifier = Modifier.padding(20.dp))
        }
        return
    }
    val g = a?.str("group") ?: ""
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(title, if (imeVisible) null else listOf(groupLabel(g), a?.str("kind") ?: "", a?.str("host") ?: "", a?.str("project") ?: "").filter { it.isNotBlank() }.joinToString(" · "), stateColor(g)) {
            LegacyAgentInterrupt(a?.optBoolean("can_stop") == true, terminal != null, busy) { perform(interrupt = true) }
        }
        if (err != null) Text(err!!, style = sans(12f, 500, p.bad), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        if (busy) Text(stringResource(R.string.agent_cancel_input), style = sans(13f, 600, p.att), modifier = Modifier
            .clickable { operation?.cancel(); feedback = str(R.string.agent_cancelled_locally) }
            .padding(horizontal = 12.dp, vertical = 8.dp))
        if (feedback != null) Text(feedback!!, style = sans(12f, 500, p.tx2), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        if (terminal == null) Text(if (screen == null) stringResource(R.string.agent_loading_screen) else stringResource(R.string.agent_read_only),
            style = sans(12f, 500, p.mu), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        else if (!imeVisible) Text(if (terminal.optBoolean("mouse")) stringResource(R.string.agent_hint_mouse) else stringResource(R.string.agent_hint_keyboard),
            style = sans(12f, 500, p.mu), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        val view = terminal ?: JSONObject().put("lines", screen?.optJSONArray("lines"))
        TerminalViewport(view, { row, col ->
            if (terminal != null && !busy) {
                if (!terminal.optBoolean("mouse")) feedback = str(R.string.agent_keyboard_only)
                else perform(input = JSONObject().put("action", "click").put("row", row).put("col", col).put("frame", terminal.str("frame")))
            }
        }, Modifier.weight(1f).fillMaxWidth())
        Column(Modifier.fillMaxWidth().background(p.bg).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (!imeVisible && a?.optBoolean("can_reply", true) != false) FleetQuickReplies(busy) { perform(draft = it) }
            if (terminal != null) TerminalControls(::input, busy, compact = imeVisible)
            if (a?.optBoolean("can_reply", true) != false) {
                FleetComposer(text, { text = it }, { perform(draft = text) }, busy, stringResource(R.string.fleet_reply_to, title), compact = imeVisible)
            } else Text(if (a?.str("via") == "desktop") stringResource(R.string.agent_reply_in_desktop) else stringResource(R.string.agent_reply_in_terminal), style = sans(13.5f, 400, p.mu), modifier = Modifier.padding(8.dp))
        }
    }
}
