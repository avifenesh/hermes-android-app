@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.avifenesh.hermes

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import org.json.JSONObject

// ---------------------------------------------------------------- pulse
@Composable
fun PulseScreen() {
    val p = P
    val appr by Hub.approvals.collectAsState()
    val work by Hub.working.collectAsState()
    val chats by Hub.chats.collectAsState()
    var pulse by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    suspend fun load() { runCatching { pulse = Api.get("/v1/pulse"); err = null }.onFailure { err = it.message } }
    val top = Ui.stack.isEmpty()
    LaunchedEffect(top) { while (top) { load(); delay(15000) } }
    val pending = appr.values.filter { it.state == "pending" }.sortedByDescending { it.ts }
    val recent = chats.values.filter { !it.archived && it.lastRole != "user" && it.updated > nowS() - 86400 }.sortedByDescending { it.updated }.take(8)
    val live = pulse?.optJSONArray("live_sessions").objs().filter { it.str("source") != "mobile" }
    val ptr = rememberPullToRefreshState()
    PullToRefreshBox(refreshing, { scope.launch { refreshing = true; load(); refreshing = false } }, Modifier.fillMaxSize(), state = ptr,
        indicator = { PullToRefreshDefaults.Indicator(state = ptr, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter), containerColor = p.s1, color = p.acc) }) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        item { Header("Pulse", "What needs you, what's running, what's done") }
        if (err != null) item { ErrorPanel("Can't reach Hermes: $err") { scope.launch { load() } } }
        else if (pulse == null) item { Skeleton(2) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("spent today", pulse?.optDouble("spend_today")?.takeIf { !it.isNaN() }?.let { money(it) } ?: "–", Modifier.weight(1f))
                Stat("runs today", pulse?.optInt("runs_today")?.toString() ?: "–", Modifier.weight(1f))
                Stat("cron fails", pulse?.optInt("failures_today")?.toString() ?: "–", Modifier.weight(1f))
            }
        }
        item { Box(Modifier.clickable { Ui.push(Route.UsageR) }) { SpendBars(pulse) } }
        item { Section("Needs you", if (pending.isEmpty()) "nothing" else pending.size.toString(), if (pending.isEmpty()) p.mu else p.att) }
        if (pending.isEmpty()) item { Text("No approvals waiting.", style = sans(14f, 400, p.mu), modifier = Modifier.padding(horizontal = 20.dp)) }
        items(pending, key = { "a" + it.id }) { a ->
            val m = Msg("x" + a.id, a.chatId, "agent", "approval", "", JSONObject().put("approval_id", a.id), a.ts, 0)
            Column {
                Text(chats[a.chatId]?.title ?: "Chat", style = sans(13f, 600, p.tx2), modifier = Modifier.padding(start = 20.dp, top = 6.dp).clickable { Ui.open(a.chatId) })
                ApprovalCard(a, m)
            }
        }
        item { FleetSection() }
        item { Section("Hermes runs", if (work.isEmpty() && live.isEmpty()) "idle" else (work.size + live.size).toString(), if (work.isEmpty()) p.mu else p.acct) }
        items(work.entries.toList(), key = { "w" + it.key }) { (cid, since) ->
            ListRow({ LiveDot(9.dp) }, chats[cid]?.title ?: "Run", "from this phone", { Elapsed(since, mono(13f, 600, p.acct)) }, { Ui.push(Route.RunR(cid)) })
        }
        items(live, key = { "l" + it.str("id") }) { s ->
            ListRow({ Dot(p.acct.copy(alpha = 0.6f), 9.dp) }, s.str("title").ifBlank { "Untitled session" }, s.str("source") + " · " + s.str("model").substringAfterLast('/'),
                { Text(rel(s.optDouble("last", 0.0)), style = mono(12f, 500, p.mu)) }, { Ui.push(Route.SessR(s.str("id"), s.str("title").ifBlank { s.str("source") }, s.str("source"))) })
        }
        item { Section("Recently done", null) }
        if (recent.isEmpty()) item { Text("Nothing in the last day.", style = sans(14f, 400, p.mu), modifier = Modifier.padding(horizontal = 20.dp)) }
        items(recent, key = { "r" + it.id }) { c ->
            ListRow({ Icn(if (c.kind == "digest") Ic.digest else Ic.check, if (c.kind == "digest") p.tx2 else p.good, 18.dp) }, c.title, c.preview,
                { Text(rel(c.updated), style = mono(11.5f, 500, p.mu)) }, { Ui.open(c.id) })
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
    }
}

@Composable
fun SpendBars(pulse: JSONObject?) {
    val p = P
    val a = pulse?.optJSONArray("spend_7d") ?: return
    val v = (0 until a.length()).map { a.optDouble(it, 0.0) }
    val mx = (v.maxOrNull() ?: 0.0).coerceAtLeast(0.0001)
    Panel(Modifier.padding(top = 6.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
            Text("7 days", style = mono(11.5f, 600, p.mu))
            Spacer(Modifier.weight(1f))
            Text(money(v.sum()), style = mono(11.5f, 600, p.tx2))
            Spacer(Modifier.width(12.dp))
            Text("Usage ›", style = sans(13f, 600, p.acct))
        }
        Canvas(Modifier.fillMaxWidth().height(70.dp).padding(16.dp)) {
            val w = size.width / v.size
            v.forEachIndexed { i, x ->
                val h = (x / mx).toFloat() * size.height
                drawRoundRect(if (i == v.lastIndex) p.acc else p.s4, Offset(i * w + w * 0.18f, size.height - h.coerceAtLeast(3f)), Size(w * 0.64f, h.coerceAtLeast(3f)), CornerRadius(6f, 6f))
            }
        }
    }
}

// ---------------------------------------------------------------- cron
@Composable
fun CronScreen() {
    val p = P
    val scope = rememberCoroutineScope()
    var jobs by remember { mutableStateOf<List<JSONObject>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var showOff by remember { mutableStateOf(false) }
    suspend fun load() { runCatching { jobs = Api.get("/v1/cron").optJSONArray("jobs").objs(); err = null }.onFailure { err = it.message } }
    val top = Ui.stack.isEmpty()
    LaunchedEffect(top) { while (top) { load(); delay(30000) } }  // reload when returning from a job/editor
    val all = jobs.orEmpty().sortedBy { parseTime(it.opt("next_run_at")) ?: Double.MAX_VALUE }
    val on = all.filter { it.optBoolean("enabled", true) }
    val off = all.filter { !it.optBoolean("enabled", true) }
    var refreshing by remember { mutableStateOf(false) }
    val ptr = rememberPullToRefreshState()
    PullToRefreshBox(refreshing, { scope.launch { refreshing = true; load(); refreshing = false } }, Modifier.fillMaxSize(), state = ptr,
        indicator = { PullToRefreshDefaults.Indicator(state = ptr, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter), containerColor = p.s1, color = p.acc) }) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        item { Header("Cron", jobs?.let { "${on.size} active · ${off.size} paused" }) {
            IconBtn(Ic.plus, p.ink, 44.dp, p.acc) { Ui.push(Route.CronEditR(null)) }
        } }
        if (err != null) item { ErrorPanel("Can't reach Hermes: $err") { scope.launch { load() } } }
        else if (jobs == null) item { Skeleton() }
        else if (all.isEmpty()) item { EmptyState("No cron jobs", "Tap + to schedule one.") }
        if (on.isNotEmpty()) item { DayStrip(on.mapNotNull { parseTime(it.opt("next_run_at")) }) }
        val next = on.firstOrNull()
        if (next != null) item {
            Panel(Modifier.padding(top = 6.dp), border = p.accd) {
                Column(Modifier.clickable { Ui.push(Route.JobR(next.str("id"))) }.padding(16.dp)) {
                    Text("NEXT UP", style = mono(11f, 700, p.acct))
                    Spacer(Modifier.height(6.dp))
                    Text(next.str("name").ifBlank { next.str("id") }, style = serif(30f, p.tx), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text((parseTime(next.opt("next_run_at"))?.let { until(it) } ?: "–") + " · " + next.str("schedule_display").ifBlank { next.str("schedule") }, style = mono(12.5f, 500, p.tx2))
                }
            }
        }
        item { Section("Active", on.size.toString()) }
        items(on, key = { it.str("id") }) { j -> JobRow(j) { scope.launch { load() } } }
        if (off.isNotEmpty()) {
            item { Row(Modifier.clickable { showOff = !showOff }) { Section("Paused", if (showOff) "hide" else "show ${off.size}") } }
            if (showOff) items(off, key = { it.str("id") }) { j -> JobRow(j) { scope.launch { load() } } }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
    }
}

@Composable
fun JobRow(j: JSONObject, reload: () -> Unit) {
    val p = P
    val scope = rememberCoroutineScope()
    val id = j.str("id")
    var enabled by remember(j.toString()) { mutableStateOf(j.optBoolean("enabled", true)) }
    var confirm by remember { mutableStateOf(false) }
    val st = j.str("last_status")
    val ok = st.isBlank() || st == "ok" || st == "success"
    val nx = parseTime(j.opt("next_run_at"))
    val lr = parseTime(j.opt("last_run_at"))
    val sub = listOfNotNull(j.str("schedule_display").ifBlank { j.str("schedule") }.takeIf { it.isNotBlank() },
        if (enabled && nx != null) until(nx) else null, lr?.let { "ran " + rel(it) + (if (!ok) " · failed" else "") }).joinToString(" · ")
    ListRow({ Dot(when { !enabled -> p.dim; !ok -> p.bad; else -> p.good }, 8.dp) }, j.str("name").ifBlank { id }, sub, {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.play, p.tx2, 40.dp) { confirm = true }
            Toggle(enabled, { v ->
                enabled = v
                scope.launch { runCatching { Api.post("/v1/cron/$id/${if (v) "resume" else "pause"}") }.onFailure { enabled = !v; Ui.say(it.message) }; reload() }
            })
        }
    }, { Ui.push(Route.JobR(id)) })
    if (confirm) HDialog("Run now?", onDismiss = { confirm = false }, body = {
            Text(j.str("name").ifBlank { id } + " will run on the next scheduler tick.", style = sans(15f, 400, p.tx2))
        }) {
            Btn("Cancel", primary = false) { confirm = false }
            Btn("Run") { confirm = false; scope.launch { runCatching { Api.post("/v1/cron/$id/run"); Ui.say("Queued") }.onFailure { Ui.say(it.message) }; reload() } }
        }
}

@Composable
fun JobScreen(id: String) {
    val p = P
    var job by remember { mutableStateOf<JSONObject?>(null) }
    var run by remember { mutableStateOf<String?>(null) }
    var out by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(id, retry) { runCatching { job = Api.get("/v1/cron").optJSONArray("jobs").objs().firstOrNull { it.str("id") == id } }.onFailure { err = it.message } }
    LaunchedEffect(id, run, retry) {
        out = null; err = null
        runCatching { val o = Api.get("/v1/cron/$id/output" + (run?.let { "?run=" + Uri.encode(it) } ?: "")); out = o.str("text"); if (run == null) run = o.str("run") }
            .onFailure { out = ""; err = if (it is ApiError && it.code == 404) "No output saved yet" else it.message }
    }
    val j = job
    val runs = j?.optJSONArray("runs").strs()
    Column(Modifier.fillMaxSize()) {
        TopBar(j?.str("name")?.ifBlank { null } ?: id, j?.let { it.str("schedule_display").ifBlank { it.str("schedule") } }) {
            IconBtn(Ic.edit, p.tx2) { Ui.push(Route.CronEditR(id)) }
        }
        LazyColumn(Modifier.weight(1f)) {
            if (j != null) item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    KV("status", if (j.optBoolean("enabled", true)) "active" else ("paused" + (j.str("paused_reason").takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")))
                    parseTime(j.opt("next_run_at"))?.let { KV("next", until(it)) }
                    parseTime(j.opt("last_run_at"))?.let { KV("last", rel(it) + " ago · " + j.str("last_status").ifBlank { "ok" }) }
                    if (j.str("model").isNotBlank()) KV("model", j.str("model"))
                    if (j.str("deliver").isNotBlank()) KV("deliver", j.str("deliver"))
                    if (j.str("last_error").isNotBlank()) Text(j.str("last_error"), style = mono(12f, 500, p.bad))
                }
            }
            if (runs.isNotEmpty()) item {
                LazyRow(Modifier.padding(vertical = 4.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(runs) { r -> Chip(r.replace('_', ' ').take(16), r == run) { run = r } }
                }
            }
            item {
                Box(Modifier.padding(16.dp)) {
                    val o = out
                    when {
                        o == null -> Skeleton(6)
                        o.isBlank() -> if (err != null) ErrorPanel(err!!) { retry++ } else Text("Empty output", style = sans(14f, 400, p.mu))
                        else -> Md(o.take(40000))
                    }
                }
            }
            if (j != null && j.str("prompt").isNotBlank()) item {
                Section("Prompt", null)
                Text(j.str("prompt"), style = mono(12.5f, 400, p.tx2), modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(30.dp))
            }
        }
    }
}

@Composable
fun KV(k: String, v: String) {
    Row {
        Text(k, style = mono(12.5f, 500, P.mu), modifier = Modifier.width(76.dp))
        Text(v, style = mono(12.5f, 500, P.tx))
    }
}

// ---------------------------------------------------------------- elsewhere (CLI / Telegram / other sessions, read-only)
@Composable
fun ElsewhereScreen() {
    val p = P
    var list by remember { mutableStateOf<List<JSONObject>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    LaunchedEffect(reloadKey) { runCatching { list = Api.get("/v1/sessions").optJSONArray("sessions").objs() }.onFailure { err = it.message } }
    Column(Modifier.fillMaxSize()) {
        TopBar("Elsewhere", "Sessions from the CLI, Telegram and other apps")
        val l = list
        when {
            err != null -> ErrorPanel(err!!) { err = null; reloadKey++ }
            l == null -> Skeleton()
            l.isEmpty() -> Empty("Nothing else", "Sessions started outside the app show up here.")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(l, key = { it.str("id") }) { s ->
                    Row(Modifier.fillMaxWidth().clickable { Ui.push(Route.SessR(s.str("id"), s.str("title").ifBlank { "Untitled" }, s.str("source"))) }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Pill(s.str("source").ifBlank { "?" }, p.tx2, p.s3)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.str("title").ifBlank { "Untitled session" }, style = sans(15f, 550, p.tx), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.str("model").substringAfterLast('/') + (s.optDouble("cost", 0.0).takeIf { it > 0 }?.let { " · " + money(it) } ?: ""), style = mono(11.5f, 500, p.mu), maxLines = 1)
                        }
                        Text(rel(s.optDouble("started_at", 0.0)), style = mono(11.5f, 500, p.mu))
                    }
                }
                item { Spacer(Modifier.height(30.dp)) }
            }
        }
    }
}

@Composable
fun SessionScreen(r: Route.SessR) {
    val p = P
    var msgs by remember { mutableStateOf<List<JSONObject>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    LaunchedEffect(r.id, reloadKey) { runCatching { msgs = Api.get("/v1/sessions/${Uri.encode(r.id)}/messages").optJSONArray("messages").objs() }.onFailure { err = it.message } }
    Column(Modifier.fillMaxSize()) {
        TopBar(r.title, r.source + " · read only")
        val l = msgs
        if (l == null) { if (err != null) ErrorPanel(err!!) { err = null; reloadKey++ } else Skeleton(6) }
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
            items(l.size) { i ->
                val m = l[i]
                val mine = m.str("role") == "user"
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                    if (mine) Box(Modifier.clip(RoundedCornerShape(20.dp)).background(p.s3).padding(horizontal = 14.dp, vertical = 9.dp)) { Text(m.str("content").take(4000), style = sans(15f, 400, p.tx, 22f)) }
                    else Box(Modifier.padding(horizontal = 6.dp)) { Md(m.str("content").take(12000)) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- me
@Composable
fun MeScreen() {
    val p = P
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val meta by Hub.meta.collectAsState()
    val feat by Hub.features.collectAsState()
    val online by Hub.online.collectAsState()
    var restart by remember { mutableStateOf(false) }
    val degraded by Hub.degraded.collectAsState()
    var probs by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { Hub.refreshMe() }; runCatching { Hub.health() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        item { Header("Me") }
        item {
            Panel {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    MarkBox(44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(meta.str("name").ifBlank { Prefs.server ?: "Hermes" }, style = sans(17f, 650, p.tx))
                        Text("profile " + meta.str("profile").ifBlank { Prefs.profile ?: "default" } + " · " + meta.str("model").ifBlank { "–" }, style = mono(12f, 500, p.mu), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (online) p.good else p.dim, 8.dp); Spacer(Modifier.width(6.dp))
                        Text(if (online) "live" else "offline", style = mono(12f, 600, if (online) p.good else p.mu))
                    }
                }
            }
        }
        item { RestartBanner() }
        if (degraded.isNotEmpty()) item {
            Panel { NavRow(Ic.server, "Server problems", "${degraded.size} problem" + (if (degraded.size == 1) "" else "s") + " found by the self-check", p.bad) { probs = true } }
        }
        item { Section("Settings", null) }
        item {
            Panel {
                NavRow(Ic.sliders, "Agent", "Model, reasoning, approvals, memory, subagents, tools") { Ui.push(Route.SettingsR) }
                Line()
                NavRow(Ic.phone, "This phone", "Voice, look, wake, notifications, connection") { Ui.push(Route.PhoneR) }
            }
        }
        item { Section("Workspace", null) }
        item {
            Panel {
                NavRow(Ic.download, "Hub", "Install skills, plugins and MCP servers") { Ui.push(Route.HubR) }
                if (feat.radar) NavRow(Ic.zap, "Radar", "Every 3h: the 5 most useful things the scout found") { Ui.push(Route.RadarR) }
                Line()
                NavRow(Ic.blocks, "Skills & plugins", "Turn skills, plugins and MCP servers on or off") { Ui.push(Route.ExtR) }
                Line()
                NavRow(Ic.book, "Memory", "What ${agentName()} remembers about you") { Ui.push(Route.MemoryR) }
                Line()
                NavRow(Ic.chart, "Usage", "Spend and tokens by day, model and surface") { Ui.push(Route.UsageR) }
                if (feat.projects) {
                    Line()
                    NavRow(Ic.file, "Projects", "Repos on the machine: changes, diffs, commit, push, PRs") { Ui.push(Route.ProjectsR) }
                }
                Line()
                NavRow(Ic.search, "Search everything", "Full-text over every session on the machine") { Ui.push(Route.SearchR) }
                Line()
                NavRow(Ic.power, "Restart Hermes", "Reload config and plugins on the machine") { restart = true }
            }
        }
        item { MachinesSection() }
        item { Panel { NavRow(Ic.shield, "Privacy policy", "Offline policy: data, permissions, retention and support") { Ui.push(Route.PrivacyR) } } }
        item { Panel { NavRow(Ic.book, "Licenses", "Offline notices, model restrictions and full license texts") { Ui.push(Route.LegalR()) } } }
        item { ReportProblemRow() }
        item {
            Text("Hermes for Android " + runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty(),
                style = mono(11.5f, 500, p.dim), modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp))
        }
    }
    if (restart) RestartDialog { restart = false }
    if (probs) HDialog("Server problems", onDismiss = { probs = false }, body = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                degraded.forEach { d ->
                    Text(listOf(d.str("path"), d.optInt("status", 0).takeIf { it > 0 }?.toString().orEmpty(), d.str("error"))
                        .filter { it.isNotBlank() }.joinToString(" · "), style = mono(12f, 500, p.tx2))
                }
            }
        }) {
            Btn("Close") { probs = false }
        }
}

// ---------------------------------------------------------------- this phone
@Composable
fun PhoneSettingsScreen() {
    val p = P
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf("look") }
    var devices by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var thisId by remember { mutableStateOf("") }
    var live by remember { mutableStateOf(Prefs.live) }
    var unpair by remember { mutableStateOf(false) }
    var push by remember { mutableStateOf(Prefs.fcmCfg != null) }
    suspend fun loadDevices() { runCatching { val o = Api.get("/v1/devices"); devices = o.optJSONArray("devices").objs(); thisId = o.str("this") } }
    LaunchedEffect(Unit) { loadDevices(); push = runCatching { Push.refresh(ctx) }.getOrDefault(push) }
    Column(Modifier.fillMaxSize()) {
        TopBar("This phone", "How the app looks, speaks and stays connected")
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Seg(phoneSettingsTabs(), tab) { tab = it }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp)) {
            when (tab) {
                "control" -> channelControlSettings()
                "look" -> {
                item { Section("Theme", null) }
                item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Seg(listOf("0" to "System", "1" to "Night", "2" to "Day"), Ui.theme.intValue.toString()) { Ui.theme.intValue = it.toInt(); Prefs.theme = it.toInt() }
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                            Accents.forEachIndexed { i, (name, night, _) ->
                                val sel = Ui.accent.intValue == i
                                Box(Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                    .semantics { contentDescription = "$name accent" }
                                    .selectable(selected = sel, role = Role.RadioButton, onClick = { Ui.accent.intValue = i; Prefs.accent = i }), contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(if (sel) 34.dp else 28.dp).clip(androidx.compose.foundation.shape.CircleShape).background(night)
                                        .border(if (sel) 3.dp else 0.dp, p.tx, androidx.compose.foundation.shape.CircleShape))
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(Accents.getOrNull(Ui.accent.intValue)?.first ?: "", style = sans(14f, 500, p.tx2))
                        }
                    }
                }
                }
                "voice" -> {
                item { Section("Talk voice", null) }
                item { Panel { TalkVoicePanel() } }
                item { Section("Wake alarm", null) }
                item {
                    val wov by Wake.overview.collectAsState()
                    val nx = wov?.optJSONObject("next")
                    Panel {
                        Row(Modifier.fillMaxWidth().clickable { Ui.push(Route.WakeR) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icn(Ic.bell, p.acct, 22.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Wake alarm", style = sans(15f, 600, p.tx))
                                Text(nx?.let { "next " + clock(it.getDouble("ts")) + " · " + until(it.getDouble("ts")) } ?: "Alarms that stop only when you prove you're up", style = sans(13f, 400, p.mu, 18f))
                            }
                            Icn(Ic.chevR, p.mu, 18.dp)
                        }
                    }
                }
                item { Section("Wake word", null) }
                item { WakeWordPanel() }
                }
                "alerts" -> {
                item { Section("Notifications", null) }
                item {
                    Panel {
                        SettingRow("Push wake-ups", if (push) "On. Google wakes the phone, content comes straight from Hermes." else "Not set up on the server. The app falls back to its own connection.") {}
                        Line()
                        SettingRow("Stay connected in background", "Keeps a live connection and shows run progress. Uses a bit more battery.") {
                            Toggle(live, { v -> live = v; Prefs.live = v; LiveService.sync(ctx); if (!v && !Hub.foreground && !Prefs.phoneArmed) Hub.disconnect() })
                        }
                        Line()
                        CronDeliveryRow()
                        Line()
                        SettingRow("Channels", "Approvals, replies, cron & digests, live runs. Mute each one separately.") {
                            Btn("Open", primary = false) { ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)) }
                        }
                        ChannelBatterySettings()
                    }
                }
                }
                else -> {
                // Revoked devices are history (test pairings, old phones); only the live ones matter here.
                val shown = devices.filter { it.optInt("revoked") == 0 }
                item { Section("Devices", shown.size.toString()) }
                item {
                    Panel {
                        shown.forEachIndexed { i, d ->
                            if (i > 0) Line()
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icn(Ic.phone, if (d.optInt("revoked") == 1) p.dim else p.tx2, 20.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(d.str("name") + if (d.str("id") == thisId) " (this phone)" else "", style = sans(15f, 550, if (d.optInt("revoked") == 1) p.dim else p.tx))
                                    Text(if (d.optInt("revoked") == 1) "revoked" else "seen " + rel(d.optDouble("last_seen", 0.0)), style = mono(11.5f, 500, p.mu))
                                }
                                if (d.str("id") != thisId && d.optInt("revoked") == 0) Btn("Revoke", danger = true) {
                                    scope.launch { runCatching { Api.delete("/v1/devices/" + d.str("id")) }.onFailure { Ui.say(it.message) }; loadDevices() }
                                }
                            }
                        }
                    }
                }
                item { Section("Connection", null) }
                item {
                    Panel {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            KV("server", Prefs.base?.removePrefix("https://") ?: "–")
                            KV("security", if (Prefs.cfId != null) "Cloudflare Access + signed requests" else "signed requests")
                            KV("approval", if (Prefs.noLock) "no screen lock set" else "fingerprint or PIN")
                        }
                    }
                }
                    item {
                        Column(Modifier.padding(20.dp)) {
                            Btn("Unpair this phone", Modifier.fillMaxWidth(), danger = true) { unpair = true }
                            Spacer(Modifier.height(18.dp))
                            Text("Hermes for Android " + runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty(), style = mono(11.5f, 500, p.dim))
                        }
                    }
                }
            }
        }
    }
    if (unpair) HDialog("Unpair?", onDismiss = { unpair = false }, body = {
            Text("This phone forgets the machine and its keys. You'll need a new pairing code.", style = sans(15f, 400, p.tx2))
        }) {
            Btn("Cancel", primary = false) { unpair = false }
            Btn("Unpair", danger = true) {
            unpair = false
            scope.launch {
                runCatching { Prefs.deviceId?.let { Api.delete("/v1/devices/$it") } }
                PhoneService.disarm(ctx)
                ctx.stopService(Intent(ctx, LiveService::class.java))
                Hub.reset(); Keys.reset(); val th = Prefs.theme; Prefs.clear(); Prefs.theme = th; Ui.stack.clear(); Ui.paired.value = false
            }
        }
        }
}

@Composable
fun Line() = Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(P.line))

@Composable
fun SettingRow(title: String, sub: String, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = sans(15f, 600, P.tx))
            Text(sub, style = sans(13f, 400, P.mu, 18f))
        }
        Spacer(Modifier.width(12.dp))
        right()
    }
}


@Composable
fun WakeWordPanel() {
    val p = P
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Prefs.wakeWord) }
    var thr by remember { mutableStateOf(Prefs.wakeThreshold) }
    val st by WakeListen.state.collectAsState()
    val hits by WakeListen.hits.collectAsState()
    val activation by WakeListen.activation.collectAsState()
    val warmingUp by WakeListen.warmingUp.collectAsState()
    fun apply(v: Boolean) { on = v; Prefs.wakeWord = v; WakeListen.sync(ctx); if (!v) WakeListen.state.value = "off" }
    val perm = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) apply(true) else Ui.say("Wake word needs the microphone")
    }
    Panel {
        SettingRow("Say \u201c${WakeWord.label(Prefs.wakePhrase)}\u201d", when {
            st.startsWith("error:") -> st.removePrefix("error:")
            !on -> "Opens Talk hands-free. Runs on the phone; audio never leaves it until you say the phrase."
            st == "paused" -> "Paused while Talk has the mic"
            st == "listening" -> "Listening" + if (hits > 0) " \u00b7 heard $hits\u00d7 this session" else ""
            else -> "Starting\u2026"
        }) {
            Toggle(on, { v ->
                if (v && androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    perm.launch(android.Manifest.permission.RECORD_AUDIO) else apply(v)
            })
        }
        if (on) {
            Line()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("microWakeWord v2 · offline", style = sans(15f, 600, p.tx))
                Text("Window cutoff: ${String.format(java.util.Locale.US, "%.2f", thr)} · 5 inferences", style = mono(12f, 500, p.mu))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Manual native-cutoff adjustment, not invented calibrated sensitivity presets.
                    Btn("Lower", primary = false, enabled = thr > 0.01f) {
                        thr = (kotlin.math.round(thr * 100).toInt() - 1).coerceAtLeast(1) / 100f; Prefs.wakeThreshold = thr
                    }
                    Btn("Higher", primary = false, enabled = thr < 0.99f) {
                        thr = (kotlin.math.round(thr * 100).toInt() + 1).coerceAtMost(99) / 100f; Prefs.wakeThreshold = thr
                    }
                }
                Btn("Use recommended 0.97", primary = false) { thr = WakeWord.DEFAULT_CUTOFF; Prefs.wakeThreshold = thr }
                if (st == "listening") {
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.s3)) {
                        Box(Modifier.fillMaxWidth((activation / 255f).coerceIn(0f, 1f)).height(6.dp)
                            .background(p.acct.copy(alpha = 0.6f)))
                    }
                    Text(if (warmingUp) "Warming up / rearming" else "Activation window: ${kotlin.math.round(activation).toInt()} / 255",
                        style = mono(12f, 500, p.mu))
                }
                Text("Higher is stricter; lower may trigger on other speech. The bar is the native model's five-output activation mean, not confidence. Changing cutoff restarts warm-up. The old engine's sensitivity was reset to the model recommendation.", style = sans(12.5f, 400, p.mu, 17f))
            }
        }
    }
}


/** Next 24h at a glance: one dot per scheduled run, a line for now, ticks every 6 hours (local clock). */
@Composable
fun DayStrip(runs: List<Double>) {
    val p = P
    val now = System.currentTimeMillis() / 1000.0
    val cal = java.util.Calendar.getInstance()
    val inDay = runs.filter { it >= now && it < now + 86400 }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("NEXT 24H", style = mono(11f, 700, p.mu), modifier = Modifier.weight(1f))
            Text("${inDay.size} run" + (if (inDay.size == 1) "" else "s"), style = mono(11f, 500, p.tx2))
        }
        Spacer(Modifier.height(8.dp))
        val acc = p.acc; val tick = p.line2; val track = p.s2; val nowC = p.tx
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(14.dp)).background(track)) {
            val w = size.width; val h = size.height
            // ticks at local 00/06/12/18 inside the window
            cal.timeInMillis = (now * 1000).toLong(); cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0)
            for (step in 0..24) {
                val t = cal.timeInMillis / 1000.0 + step * 3600
                val hr = java.util.Calendar.getInstance().apply { timeInMillis = (t * 1000).toLong() }.get(java.util.Calendar.HOUR_OF_DAY)
                if (hr % 6 == 0 && t > now) { val x = ((t - now) / 86400 * w).toFloat(); drawLine(tick, androidx.compose.ui.geometry.Offset(x, h * 0.25f), androidx.compose.ui.geometry.Offset(x, h * 0.75f), 2f) }
            }
            drawLine(nowC, androidx.compose.ui.geometry.Offset(3f, 4f), androidx.compose.ui.geometry.Offset(3f, h - 4f), 4f)
            for (t in inDay) drawCircle(acc, radius = h * 0.2f, center = androidx.compose.ui.geometry.Offset(((t - now) / 86400 * w).toFloat().coerceIn(h * 0.25f, w - h * 0.25f), h / 2))
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            val fmt = java.text.SimpleDateFormat("HH:mm", LocalConfiguration.current.locales[0])
            listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEachIndexed { i, f ->
                Text(if (i == 0) "now" else fmt.format(java.util.Date(((now + f * 86400) * 1000).toLong())), style = mono(10f, 500, p.mu),
                    modifier = Modifier.weight(1f), textAlign = when (i) { 0 -> androidx.compose.ui.text.style.TextAlign.Start; 4 -> androidx.compose.ui.text.style.TextAlign.End; else -> androidx.compose.ui.text.style.TextAlign.Center })
            }
        }
    }
}

/** Talk speaks with the server's Hermes voice (xAI ara by default); phone TTS is the fallback or an explicit choice. */
@Composable
fun TalkVoicePanel() {
    val p = P
    var server by remember { mutableStateOf(Prefs.serverVoice) }
    SettingRow("Server voice", if (server) "Hermes' own voice (set under Agent → Voice). About a second before each reply." else "The phone's text-to-speech engine. Instant, robotic.") {
        Toggle(server, { v -> server = v; Prefs.serverVoice = v })
    }
}
