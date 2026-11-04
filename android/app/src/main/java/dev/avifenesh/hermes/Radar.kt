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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.LayoutBoundsHolder
import androidx.compose.ui.layout.layoutBounds
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
    val moFailed by Momentum.failed.collectAsState()
    var moTried by remember { mutableStateOf(mo != null) }
    // Both survive a rotation: recreating the activity is not leaving the page.
    val moOpen = rememberSaveable(saver = MomentumOpenSaver) { mutableStateMapOf<String, Boolean>() }
    val sightings = rememberSaveable(saver = MomentumSightings.Saver) { MomentumSightings() }
    val viewport = remember { LayoutBoundsHolder() }
    suspend fun load() {
        runCatching { data = Api.get("/v1/radar?view=$view"); err = null }.onFailure { err = it.message }
    }
    suspend fun loadMomentum(force: Boolean = false) { runCatching { Momentum.load(force) }; moTried = true }
    LaunchedEffect(view) { load() }
    // In front: the top route, with no compose sheet or curator note over it. Only then does reading count, and only
    // then does the report follow running work by the minute.
    val front = Ui.stack.lastOrNull() == Route.RadarR && !Ui.composeOpen.value && noteOpen == null
    val inFront by rememberUpdatedState(front)
    LaunchedEffect(front) { if (front) sightings.start() else sightings.stop() }
    VisibleEffect(front) { while (front) { loadMomentum(); delay(60_000) } }
    val rep = Momentum.report(mo)
    // Open state is settled when a copy first shows; a later copy only opens sections that gained unread items.
    LaunchedEffect(rep) {
        if (rep != null) for ((k, _) in Momentum.SECTIONS) {
            val unread = Momentum.unread(rep, k).isNotEmpty()
            if (k !in moOpen) moOpen[k] = unread else if (unread) moOpen[k] = true
        }
    }
    // Leaving the page (or the app) records the rows read on screen: next time a section read through shows its
    // count. A rotation recreates the activity without leaving: what was read so far is kept, nothing is committed.
    val latest by rememberUpdatedState(rep)
    val act = LocalAct.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        fun commit() {
            if (!act.isChangingConfigurations) Momentum.markRead(sightings.drain().filterKeys { moOpen[it] == true }, latest)
        }
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> if (inFront) sightings.start()
                Lifecycle.Event.ON_STOP -> { sightings.stop(); commit() }
                else -> Unit
            }
        }
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
    val momentum = m != null && m.optBoolean("available")
    // A machine with momentum but no scout gets a momentum page, without the scout's controls or promises.
    val listing = data
    // The listing knows best; until it answers, the momentum route says whether there is a scout.
    val scout = !momentum || (if (listing != null && listing.has("scout")) listing.optBoolean("scout") else m.optBoolean("scout", true))
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(stringResource(R.string.radar_title), err?.let(::bidiIsolate) ?: if (!scout) null else batch?.let { stringResource(R.string.radar_batch, rel(it.optDouble("ts")), Fmt.hours(3)) } ?: if (data == null) stringResource(R.string.common_loading) else stringResource(R.string.radar_no_batch))
        PullToRefreshBox(refreshing, {
            scope.launch { refreshing = true; coroutineScope { launch { load() }; launch { loadMomentum(force = true) } }; refreshing = false }
        }, Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().layoutBounds(viewport)) {
                if (m != null && momentum) {
                    item(key = "momentum-head") {
                        val (right, stale) = momentumWhen(m, moFailed != null)
                        Section(stringResource(R.string.radar_momentum), right, rightColor = if (stale) p.attt else p.mu)
                    }
                    item(key = "momentum") {
                        MomentumPanel(m, moOpen, sightings, viewport) { k, open ->
                            moOpen[k] = open
                            // Collapsing by hand is reading it: the section was on screen to be tapped.
                            if (!open) Momentum.markRead(mapOf(k to Momentum.shown(rep, k)), rep)
                        }
                    }
                    if (scout) item(key = "scout-head") { Section(stringResource(R.string.radar_scout_findings)) }
                } else if (m == null && !moTried) item(key = "momentum-wait") { Skeleton(1) }
                if (scout) stickyHeader(key = "views") {
                    Row(Modifier.fillMaxWidth().background(p.bg).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Seg(listOf("current" to stringResource(R.string.radar_view_latest), "saved" to stringResource(R.string.radar_view_saved), "history" to stringResource(R.string.radar_view_history)), view) { view = it }
                    }
                }
                items(notes, key = { "n" + it.str("id") }) { n ->
                    Panel(Modifier.padding(bottom = 10.dp), border = p.acc) {
                        Column(Modifier.clickable { noteOpen = n }.padding(14.dp)) {
                            Text(n.str("title"), style = sans(14.5f, 600, p.tx).content())
                            Text(n.str("body"), style = sans(13f, 400, p.mu, 18f).content(), maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.radar_tap_skill_diff), style = mono(11.5f, 500, p.acct))
                        }
                    }
                }
                if (scout && data != null && findings.isEmpty()) item {
                    Empty(if (view == "saved") stringResource(R.string.radar_nothing_saved) else stringResource(R.string.radar_nothing_yet), when (view) {
                        "current" -> stringResource(R.string.radar_empty_current)
                        "saved" -> stringResource(R.string.radar_empty_saved)
                        else -> stringResource(R.string.radar_empty_history)
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
            TopBar(n.str("title"), stringResource(R.string.radar_curator_note), contentTitle = true) { }
            LazyColumn(Modifier.weight(1f)) {
                item { Text(n.str("body"), style = sans(14.5f, 400, p.tx).content(), modifier = Modifier.padding(16.dp)) }
                item { CodeBlock(full?.str("diff").orEmpty().ifBlank { stringResource(R.string.radar_no_diff) }, "diff") }
            }
            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(stringResource(R.string.common_close), Modifier.weight(1f), primary = false, onClick = onClose)
                Btn(stringResource(R.string.radar_got_it), Modifier.weight(1f), onClick = onAck)
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
                when (action) { "feedback" -> Ui.say(R.string.radar_feedback_saved); "note" -> Ui.say(R.string.radar_note_saved); "dismiss" -> Ui.say(R.string.radar_dismissed_toast); "save" -> Ui.say(R.string.radar_saved_for_later) }
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
                else Ui.say(R.string.radar_agent_queued, o.str("task_id"))
                runCatching { Api.get("/v1/radar/findings/${Fleet.enc(id)}").optJSONObject("finding")?.let(onUpdate) }
            } catch (e: Exception) { Ui.say(e.message) } finally { busy = false }
        }
    }
    Panel(Modifier.padding(bottom = 12.dp), bg = if (state == "dismissed") p.bg else p.s1) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(f.str("type_label").ifBlank { f.str("type") }, p.ink, p.acc, content = true)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.radar_confidence, Fmt.percent(f.optDouble("confidence", 0.0))) + " · " + bidiIsolate(f.str("actionability")), style = mono(11.5f, 500, p.mu))
                Spacer(Modifier.weight(1f))
                if (state == "saved") Pill(stringResource(R.string.radar_status_saved), p.acct, p.accd)
                if (state == "dismissed") Pill(stringResource(R.string.radar_status_dismissed), p.mu, p.s3)
                if (f.optInt("spawned") > 0) { Spacer(Modifier.width(6.dp)); Pill(stringResource(R.string.radar_status_spawned), p.good, p.s3) }
            }
            Spacer(Modifier.height(8.dp))
            Text(f.str("title"), style = sans(16.5f, 650, p.tx).content())
            Spacer(Modifier.height(4.dp))
            Text(f.str("why"), style = sans(14f, 400, p.tx2, 20f).content())
            val ev = f.optJSONArray("evidence")
            if (ev != null && ev.length() > 0) {
                Spacer(Modifier.height(8.dp))
                for (i in 0 until ev.length()) {
                    val e = ev.opt(i)?.toString().orEmpty()
                    val url = Regex("https?://\\S+").find(e)?.value?.trimEnd(')', ',', '.')
                    Text("• " + e, style = mono(11.5f, 500, if (url != null) p.acct else p.mu).content(), maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (url != null && safeLink(url)) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            else { cb.setText(AnnotatedString(e)); Ui.say(R.string.common_copied) }
                        }.padding(vertical = 2.dp))
                }
            }
            f.optJSONObject("freshness")?.let { fr ->
                Spacer(Modifier.height(8.dp))
                val ok = fr.str("result") == "fresh"
                Text((if (ok) stringResource(R.string.radar_fresh, bidiIsolate(fr.str("checked"))) else stringResource(R.string.radar_downgraded, bidiIsolate(fr.str("checked")))) +
                    fr.str("resurface_reason").let { if (it.isBlank()) "" else " · " + stringResource(R.string.radar_resurfaced, bidiIsolate(it)) },
                    style = sans(12.5f, 400, if (ok) p.goodt else p.attt, 17f))
            }
            f.optJSONArray("feedback").objs().forEach { x ->
                val t = x.str("text")
                val label = when (x.str("kind")) { "note" -> stringResource(R.string.radar_feedback_note); "feedback" -> stringResource(R.string.radar_feedback_you_said); "spawn_chat" -> stringResource(R.string.radar_feedback_spawned_chat); "spawn_task" -> stringResource(R.string.radar_feedback_spawned_agent); else -> bidiIsolate(x.str("kind")) }
                Text("$label · ${rel(x.optDouble("ts"))}" + if (t.isNotBlank()) ": " + bidiIsolate(t) else "", style = sans(12.5f, 400, p.mu, 17f), modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("👍", vote > 0) { act(if (vote > 0) "clear-vote" else "like") }
                Chip("👎", vote < 0) { act(if (vote < 0) "clear-vote" else "dislike") }
                Chip(stringResource(R.string.radar_react), react) { react = !react; spawn = null }
                Chip(if (state == "saved") stringResource(R.string.common_saved) else stringResource(R.string.common_save), state == "saved") { act(if (state == "saved") "unsave" else "save") }
                Chip(if (state == "dismissed") stringResource(R.string.radar_restore) else stringResource(R.string.radar_dismiss), false) { act(if (state == "dismissed") "restore" else "dismiss") }
                Chip(stringResource(R.string.radar_spawn_chat), spawn == "chat") { spawn = if (spawn == "chat") null else "chat"; react = false }
                Chip(stringResource(R.string.radar_spawn_agent), spawn == "task") { spawn = if (spawn == "task") null else "task"; react = false }
            }
            if (react) {
                Spacer(Modifier.height(10.dp))
                InputBox(text, { text = it }, stringResource(R.string.radar_reaction_hint), Modifier.fillMaxWidth(), minLines = 2)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn(stringResource(R.string.radar_send_feedback), Modifier.weight(1f), enabled = text.isNotBlank() && !busy) { act("feedback", JSONObject().put("text", text)) }
                    Btn(stringResource(R.string.radar_save_as_note), Modifier.weight(1f), primary = false, enabled = text.isNotBlank() && !busy) { act("note", JSONObject().put("text", text)) }
                }
            }
            spawn?.let { mode ->
                Spacer(Modifier.height(10.dp))
                Text(if (mode == "chat") stringResource(R.string.radar_spawn_chat_label) else stringResource(R.string.radar_spawn_agent_label), style = mono(11.5f, 600, p.mu))
                Spacer(Modifier.height(6.dp))
                InputBox(prompt, { prompt = it }, stringResource(R.string.radar_prompt_hint), Modifier.fillMaxWidth(), minLines = 3, maxLines = 12, size = 14f)
                Spacer(Modifier.height(8.dp))
                Btn(if (mode == "chat") stringResource(R.string.radar_start_chat) else stringResource(R.string.radar_start_agent), Modifier.fillMaxWidth(), enabled = prompt.isNotBlank() && !busy) { doSpawn(mode) }
            }
        }
    }
}
