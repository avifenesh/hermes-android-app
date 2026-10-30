package dev.avifenesh.hermes

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Radar: momentum's report on top, then the scout's latest batch of findings, saved ones and history. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RadarScreen() {
    val p = P
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf("current") }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var noteOpen by remember { mutableStateOf<JSONObject?>(null) }
    val mo by Momentum.state.collectAsState()
    var moTried by remember { mutableStateOf(mo != null) }
    val moOpen = remember { mutableStateMapOf<String, Boolean>() }
    val moSeen = remember { mutableSetOf<String>() }
    suspend fun load() {
        runCatching { data = Api.get("/v1/radar?view=$view"); err = null }.onFailure { err = it.message }
    }
    suspend fun loadMomentum(force: Boolean = false) { runCatching { Momentum.load(force) }; moTried = true }
    LaunchedEffect(view) { load() }
    // Running work changes by the minute, so the report follows while this page is on screen.
    val top = Ui.stack.lastOrNull() == Route.RadarR && !Ui.composeOpen.value
    VisibleEffect(top) { while (top) { loadMomentum(); delay(60_000) } }
    val rep = Momentum.report(mo)
    // Open state is settled when a copy first shows; a later copy only opens sections that gained unread items.
    LaunchedEffect(rep) {
        if (rep != null) for ((k, _) in Momentum.SECTIONS) {
            val unread = Momentum.unread(rep, k).isNotEmpty()
            if (k !in moOpen) moOpen[k] = unread else if (unread) moOpen[k] = true
        }
    }
    // Leaving the page (or the app) marks what was on screen as read: next time those sections show their count.
    val latest by rememberUpdatedState(rep)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        fun commit() { Momentum.markRead(latest, moSeen.filter { moOpen[it] == true }); moSeen.clear() }
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) commit() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); commit() }
    }
    val batch = data?.optJSONObject("batch")
    val findings = data?.optJSONArray("findings").objs()
    val notes = data?.optJSONArray("notes").objs().filter { it.optInt("acked") == 0 }
    fun replace(f: JSONObject) {
        val d = data ?: return
        val arr = d.optJSONArray("findings") ?: return
        for (i in 0 until arr.length()) if (arr.optJSONObject(i)?.str("id") == f.str("id")) arr.put(i, f)
        data = JSONObject(d.toString())
    }
    val m = mo
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar("Radar", err ?: batch?.let { "Batch ${rel(it.optDouble("ts"))} · every 3h" } ?: if (data == null) "Loading…" else "No batch yet")
        PullToRefreshBox(refreshing, {
            scope.launch { refreshing = true; coroutineScope { launch { load() }; launch { loadMomentum(force = true) } }; refreshing = false }
        }, Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (m != null && m.optBoolean("available")) {
                    item(key = "momentum-head") { Section("Momentum", momentumWhen(m), rightColor = if (m.optBoolean("stale")) p.att else p.mu) }
                    item(key = "momentum") {
                        MomentumPanel(m, moOpen, moSeen) { k, open ->
                            moOpen[k] = open
                            if (!open) Momentum.markRead(rep, listOf(k))
                        }
                    }
                    item(key = "scout-head") { Section("Scout findings") }
                } else if (m == null && !moTried) item(key = "momentum-wait") { Skeleton(1) }
                stickyHeader(key = "views") {
                    Row(Modifier.fillMaxWidth().background(p.bg).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Seg(listOf("current" to "Latest", "saved" to "Saved", "history" to "History"), view) { view = it }
                    }
                }
                items(notes, key = { "n" + it.str("id") }) { n ->
                    Panel(Modifier.padding(bottom = 10.dp), border = p.acc) {
                        Column(Modifier.clickable { noteOpen = n }.padding(14.dp)) {
                            Text(n.str("title"), style = sans(14.5f, 600, p.tx))
                            Text(n.str("body"), style = sans(13f, 400, p.mu, 18f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text("Tap to see the skill diff", style = mono(11.5f, 500, p.acct))
                        }
                    }
                }
                if (data != null && findings.isEmpty()) item {
                    Empty(if (view == "saved") "Nothing saved" else "Nothing yet", when (view) {
                        "current" -> "The scout runs every 3 hours. Pull to refresh."
                        "saved" -> "Findings you save show up here."
                        else -> "Past scout findings show up here."
                    })
                }
                items(findings, key = { it.str("id") }) { f -> FindingCard(f, ::replace) }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }
    noteOpen?.let { n -> NoteSheet(n, onClose = { noteOpen = null }) { scope.launch { runCatching { Api.post("/v1/radar/notes/${Fleet.enc(n.str("id"))}/ack") }; noteOpen = null; load() } } }
}

@Composable
private fun NoteSheet(n: JSONObject, onClose: () -> Unit, onAck: () -> Unit) {
    val p = P
    var full by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(n.str("id")) { full = runCatching { Api.get("/v1/radar/notes/${Fleet.enc(n.str("id"))}").optJSONObject("note") }.getOrNull() }
    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(n.str("title"), "Curator note") { }
            LazyColumn(Modifier.weight(1f)) {
                item { Text(n.str("body"), style = sans(14.5f, 400, p.tx), modifier = Modifier.padding(16.dp)) }
                item { CodeBlock(full?.str("diff").orEmpty().ifBlank { "(no diff)" }, "diff") }
            }
            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("Close", Modifier.weight(1f), primary = false, onClick = onClose)
                Btn("Got it", Modifier.weight(1f), onClick = onAck)
            }
        }
    }
}

@Composable
fun FindingCard(f: JSONObject, onUpdate: (JSONObject) -> Unit) {
    val p = P
    val ctx = LocalContext.current
    val cb = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val id = f.str("id")
    var react by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var spawn by remember { mutableStateOf<String?>(null) }
    var prompt by remember(id) { mutableStateOf(f.str("prompt")) }
    var busy by remember { mutableStateOf(false) }
    val vote = f.optInt("vote")
    val state = f.str("state")
    fun act(action: String, body: JSONObject = JSONObject()) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                Api.post("/v1/radar/findings/${Fleet.enc(id)}/$action", body).optJSONObject("finding")?.let(onUpdate)
                when (action) { "feedback" -> Ui.say("Feedback saved"); "note" -> Ui.say("Note saved"); "dismiss" -> Ui.say("Dismissed"); "save" -> Ui.say("Saved for later") }
                if (action == "feedback" || action == "note") { text = ""; react = false }
            } catch (e: Exception) { Ui.say(e.message) } finally { busy = false }
        }
    }
    fun doSpawn(mode: String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val o = Api.post("/v1/radar/findings/${Fleet.enc(id)}/spawn", JSONObject().put("mode", mode).put("prompt", prompt))
                spawn = null
                if (mode == "chat") o.str("chat_id").takeIf { it.isNotBlank() }?.let { Ui.open(it) }
                else Ui.say("Background agent queued: " + o.str("task_id"))
                runCatching { Api.get("/v1/radar/findings/${Fleet.enc(id)}").optJSONObject("finding")?.let(onUpdate) }
            } catch (e: Exception) { Ui.say(e.message) } finally { busy = false }
        }
    }
    Panel(Modifier.padding(bottom = 12.dp), bg = if (state == "dismissed") p.bg else p.s1) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(f.str("type_label").ifBlank { f.str("type") }, p.ink, p.acc)
                Spacer(Modifier.width(8.dp))
                Text("conf ${"%.0f".format(f.optDouble("confidence", 0.0) * 100)}% · ${f.str("actionability")}", style = mono(11.5f, 500, p.mu))
                Spacer(Modifier.weight(1f))
                if (state == "saved") Pill("saved", p.acct, p.accd)
                if (state == "dismissed") Pill("dismissed", p.mu, p.s3)
                if (f.optInt("spawned") > 0) { Spacer(Modifier.width(6.dp)); Pill("spawned", p.good, p.s3) }
            }
            Spacer(Modifier.height(8.dp))
            Text(f.str("title"), style = sans(16.5f, 650, p.tx))
            Spacer(Modifier.height(4.dp))
            Text(f.str("why"), style = sans(14f, 400, p.tx2, 20f))
            val ev = f.optJSONArray("evidence")
            if (ev != null && ev.length() > 0) {
                Spacer(Modifier.height(8.dp))
                for (i in 0 until ev.length()) {
                    val e = ev.opt(i)?.toString().orEmpty()
                    val url = Regex("https?://\\S+").find(e)?.value?.trimEnd(')', ',', '.')
                    Text("• " + e, style = mono(11.5f, 500, if (url != null) p.acct else p.mu), maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (url != null && safeLink(url)) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            else { cb.setText(AnnotatedString(e)); Ui.say("Copied") }
                        }.padding(vertical = 2.dp))
                }
            }
            f.optJSONObject("freshness")?.let { fr ->
                Spacer(Modifier.height(8.dp))
                val ok = fr.str("result") == "fresh"
                Text((if (ok) "✓ fresh: " else "↓ downgraded: ") + fr.str("checked") + fr.str("resurface_reason").let { if (it.isBlank()) "" else " · resurfaced: $it" },
                    style = sans(12.5f, 400, if (ok) p.good else p.att, 17f))
            }
            f.optJSONArray("feedback").objs().forEach { x ->
                val t = x.str("text")
                val label = when (x.str("kind")) { "note" -> "note"; "feedback" -> "you said"; "spawn_chat" -> "spawned chat"; "spawn_task" -> "spawned agent"; else -> x.str("kind") }
                Text("$label · ${rel(x.optDouble("ts"))}" + if (t.isNotBlank()) ": $t" else "", style = sans(12.5f, 400, p.mu, 17f), modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("👍", vote > 0) { act(if (vote > 0) "clear-vote" else "like") }
                Chip("👎", vote < 0) { act(if (vote < 0) "clear-vote" else "dislike") }
                Chip("React", react) { react = !react; spawn = null }
                Chip(if (state == "saved") "Saved" else "Save", state == "saved") { act(if (state == "saved") "unsave" else "save") }
                Chip(if (state == "dismissed") "Restore" else "Dismiss", false) { act(if (state == "dismissed") "restore" else "dismiss") }
                Chip("Spawn chat", spawn == "chat") { spawn = if (spawn == "chat") null else "chat"; react = false }
                Chip("Spawn agent", spawn == "task") { spawn = if (spawn == "task") null else "task"; react = false }
            }
            if (react) {
                Spacer(Modifier.height(10.dp))
                InputBox(text, { text = it }, "Your reaction, in your own words…", Modifier.fillMaxWidth(), minLines = 2)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Send feedback", Modifier.weight(1f), enabled = text.isNotBlank() && !busy) { act("feedback", JSONObject().put("text", text)) }
                    Btn("Save as note", Modifier.weight(1f), primary = false, enabled = text.isNotBlank() && !busy) { act("note", JSONObject().put("text", text)) }
                }
            }
            spawn?.let { mode ->
                Spacer(Modifier.height(10.dp))
                Text(if (mode == "chat") "New chat with this prompt" else "Background agent (kanban task) with this prompt", style = mono(11.5f, 600, p.mu))
                Spacer(Modifier.height(6.dp))
                InputBox(prompt, { prompt = it }, "Prompt", Modifier.fillMaxWidth(), minLines = 3, maxLines = 12, size = 14f)
                Spacer(Modifier.height(8.dp))
                Btn(if (mode == "chat") "Start chat" else "Start agent", Modifier.fillMaxWidth(), enabled = prompt.isNotBlank() && !busy) { doSpawn(mode) }
            }
        }
    }
}
