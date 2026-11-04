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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val top = Ui.stack.isEmpty() && !Ui.composeOpen.value
    VisibleEffect(top) { while (top) { load(); delay(15000) } }
    val pending = appr.values.filter { it.state == "pending" }.sortedByDescending { it.ts }
    val recent = chats.values.filter { !it.archived && it.lastRole != "user" && it.updated > nowS() - 86400 }.sortedByDescending { it.updated }.take(8)
    val live = pulse?.optJSONArray("live_sessions").objs().filter { it.str("source") != "mobile" }
    val ptr = rememberPullToRefreshState()
    PullToRefreshBox(refreshing, { scope.launch { refreshing = true; load(); refreshing = false } }, Modifier.fillMaxSize(), state = ptr,
        indicator = { PullToRefreshDefaults.Indicator(state = ptr, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter), containerColor = p.s1, color = p.acc) }) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        item { Header(stringResource(R.string.pulse_title), stringResource(R.string.pulse_subtitle)) }
        if (err != null) item { ErrorPanel(stringResource(R.string.pulse_unreachable, bidiIsolate(HERMES), bidiIsolate(err.orEmpty()))) { scope.launch { load() } } }
        else if (pulse == null) item { Skeleton(2) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat(stringResource(R.string.pulse_stat_spent_today), pulse?.optDouble("spend_today")?.takeIf { !it.isNaN() }?.let { money(it) } ?: "–", Modifier.weight(1f))
                Stat(stringResource(R.string.pulse_stat_runs_today), pulse?.optInt("runs_today")?.toString() ?: "–", Modifier.weight(1f))
                Stat(stringResource(R.string.pulse_stat_cron_fails), pulse?.optInt("failures_today")?.toString() ?: "–", Modifier.weight(1f))
            }
        }
        item { Box(Modifier.clickable { Ui.push(Route.UsageR) }) { SpendBars(pulse) } }
        item { Section(stringResource(R.string.pulse_needs_you), if (pending.isEmpty()) stringResource(R.string.pulse_needs_you_nothing) else Fmt.int(pending.size), if (pending.isEmpty()) p.mu else p.att) }
        if (pending.isEmpty()) item { Text(stringResource(R.string.pulse_no_approvals), style = sans(14f, 400, p.mu), modifier = Modifier.padding(horizontal = 20.dp)) }
        items(pending, key = { "a" + it.id }) { a ->
            val m = Msg("x" + a.id, a.chatId, "agent", "approval", "", JSONObject().put("approval_id", a.id), a.ts, 0)
            Column {
                Text(chats[a.chatId]?.title ?: stringResource(R.string.pulse_chat_fallback), style = sans(13f, 600, p.tx2).let { if (chats[a.chatId]?.title != null) it.content() else it }, modifier = Modifier.padding(start = 20.dp, top = 6.dp).clickable { Ui.open(a.chatId) })
                ApprovalCard(a, m)
            }
        }
        item { FleetSection() }
        item { Section(stringResource(R.string.pulse_hermes_runs, bidiIsolate(HERMES)), if (work.isEmpty() && live.isEmpty()) stringResource(R.string.pulse_runs_idle) else Fmt.int(work.size + live.size), if (work.isEmpty()) p.mu else p.acct) }
        items(work.entries.toList(), key = { "w" + it.key }) { (cid, since) ->
            ListRow({ LiveDot(9.dp) }, chats[cid]?.title ?: stringResource(R.string.pulse_run_fallback), stringResource(R.string.pulse_run_from_phone), { Elapsed(since, mono(13f, 600, p.acct)) }, { Ui.push(Route.RunR(cid)) }, contentTitle = chats[cid]?.title != null)
        }
        items(live, key = { "l" + it.str("id") }) { s ->
            ListRow({ Dot(p.acct.copy(alpha = 0.6f), 9.dp) }, s.str("title").ifBlank { stringResource(R.string.pulse_untitled_session) }, bidiJoin(listOf(s.str("source"), s.str("model").substringAfterLast('/'))),
                { Text(rel(s.optDouble("last", 0.0)), style = mono(12f, 500, p.mu)) }, { Ui.push(Route.SessR(s.str("id"), s.str("title").ifBlank { s.str("source") }, s.str("source"))) },
                contentTitle = s.str("title").isNotBlank())
        }
        item { Section(stringResource(R.string.pulse_recently_done), null) }
        if (recent.isEmpty()) item { Text(stringResource(R.string.pulse_nothing_last_day), style = sans(14f, 400, p.mu), modifier = Modifier.padding(horizontal = 20.dp)) }
        items(recent, key = { "r" + it.id }) { c ->
            ListRow({ Icn(if (c.kind == "digest") Ic.digest else Ic.check, if (c.kind == "digest") p.tx2 else p.good, 18.dp) }, c.title, c.preview,
                { Text(rel(c.updated), style = mono(11.5f, 500, p.mu)) }, { Ui.open(c.id) }, contentTitle = true, contentSub = true)
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
            Text(pluralStringResource(R.plurals.spend_days, 7, 7), style = mono(11.5f, 600, p.mu))
            Spacer(Modifier.weight(1f))
            Text(money(v.sum()), style = mono(11.5f, 600, p.tx2))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.spend_usage_link), style = sans(13f, 600, p.acct))
        }
        Canvas(Modifier.fillMaxWidth().height(70.dp).padding(16.dp).mirrorInRtl()) {
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
    var seg by rememberSaveable { mutableStateOf("jobs") }
    var jobs by remember { mutableStateOf<List<JSONObject>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var showOff by remember { mutableStateOf(false) }
    suspend fun load() { runCatching { jobs = Api.get("/v1/cron").optJSONArray("jobs").objs(); err = null }.onFailure { err = it.message } }
    val top = Ui.stack.isEmpty() && !Ui.composeOpen.value && seg == "jobs"
    VisibleEffect(top) { while (top) { load(); delay(30000) } }  // reload when returning from a job/editor
    val all = jobs.orEmpty().sortedBy { parseTime(it.opt("next_run_at")) ?: Double.MAX_VALUE }
    val on = all.filter { it.optBoolean("enabled", true) }
    val off = all.filter { !it.optBoolean("enabled", true) }
    var refreshing by remember { mutableStateOf(false) }
    val ptr = rememberPullToRefreshState()
    val wov by Wake.overview.collectAsState()
    val wnext = wov?.optJSONObject("next")
    Column(Modifier.fillMaxSize()) {
        Header(if (seg == "alarms") stringResource(R.string.cron_alarm_clock) else stringResource(R.string.cron_title),
            if (seg == "alarms") wnext?.let { stringResource(R.string.cron_alarm_next, clock(it.getDouble("ts")), until(it.getDouble("ts"))) } ?: stringResource(R.string.cron_no_alarm)
            else jobs?.let { pluralStringResource(R.plurals.cron_active_count, on.size, on.size) + " · " + pluralStringResource(R.plurals.cron_paused_count, off.size, off.size) }) {
            if (seg == "jobs") IconBtn(Ic.plus, p.ink, 44.dp, p.acc) { Ui.push(Route.CronEditR(null)) }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Seg(listOf("jobs" to stringResource(R.string.cron_tab_jobs), "alarms" to stringResource(R.string.cron_alarm_clock)), seg) { seg = it }
        }
        if (seg == "alarms") {
            WakeSettingsScreen(Modifier.weight(1f), embedded = true)
            return@Column
        }
    PullToRefreshBox(refreshing, { scope.launch { refreshing = true; load(); refreshing = false } }, Modifier.weight(1f), state = ptr,
        indicator = { PullToRefreshDefaults.Indicator(state = ptr, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter), containerColor = p.s1, color = p.acc) }) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        if (err != null) item { ErrorPanel(stringResource(R.string.cron_unreachable, bidiIsolate(HERMES), bidiIsolate(err.orEmpty()))) { scope.launch { load() } } }
        else if (jobs == null) item { Skeleton() }
        else if (all.isEmpty()) item { EmptyState(stringResource(R.string.cron_empty_title), stringResource(R.string.cron_empty_sub)) }
        if (on.isNotEmpty()) item { DayStrip(on.mapNotNull { parseTime(it.opt("next_run_at")) }) }
        val next = on.firstOrNull()
        if (next != null) item {
            Panel(Modifier.padding(top = 6.dp), border = p.accd) {
                Column(Modifier.clickable { Ui.push(Route.JobR(next.str("id"))) }.padding(16.dp)) {
                    Text(stringResource(R.string.cron_next_up), style = mono(11f, 700, p.acct))
                    Spacer(Modifier.height(6.dp))
                    Text(next.str("name").ifBlank { next.str("id") }, style = serif(30f, p.tx).trackFor(next.str("name").ifBlank { next.str("id") }).contentLine(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text((parseTime(next.opt("next_run_at"))?.let { until(it) } ?: "–") + " · " + bidiIsolate(next.str("schedule_display").ifBlank { next.str("schedule") }), style = mono(12.5f, 500, p.tx2))
                }
            }
        }
        item { Section(stringResource(R.string.cron_section_active), Fmt.int(on.size)) }
        items(on, key = { it.str("id") }) { j -> JobRow(j) { scope.launch { load() } } }
        if (off.isNotEmpty()) {
            item { Row(Modifier.clickable { showOff = !showOff }) { Section(stringResource(R.string.cron_section_paused), if (showOff) stringResource(R.string.cron_paused_hide) else stringResource(R.string.cron_paused_show, off.size)) } }
            if (showOff) items(off, key = { it.str("id") }) { j -> JobRow(j) { scope.launch { load() } } }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
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
    val sub = listOfNotNull(j.str("schedule_display").ifBlank { j.str("schedule") }.takeIf { it.isNotBlank() }?.let(::bidiIsolate),
        if (enabled && nx != null) until(nx) else null, lr?.let { stringResource(if (ok) R.string.cron_job_ran else R.string.cron_job_ran_failed, rel(it)) }).joinToString(" · ")
    ListRow({ Dot(when { !enabled -> p.dim; !ok -> p.bad; else -> p.good }, 8.dp) }, j.str("name").ifBlank { id }, sub, {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.play, p.tx2, 40.dp) { confirm = true }
            Toggle(enabled, { v ->
                enabled = v
                scope.launch { runCatching { Api.post("/v1/cron/$id/${if (v) "resume" else "pause"}") }.onFailure { enabled = !v; Ui.say(it.message) }; reload() }
            })
        }
    }, { Ui.push(Route.JobR(id)) }, contentTitle = true)
    if (confirm) HDialog(stringResource(R.string.cron_run_now_title), onDismiss = { confirm = false }, body = {
            Text(stringResource(R.string.cron_run_now_body, bidiIsolate(j.str("name").ifBlank { id })), style = sans(15f, 400, p.tx2))
        }) {
            Btn(stringResource(R.string.common_cancel), primary = false) { confirm = false }
            Btn(stringResource(R.string.common_run)) { confirm = false; scope.launch { runCatching { Api.post("/v1/cron/$id/run"); Ui.say(R.string.cron_queued) }.onFailure { Ui.say(it.message) }; reload() } }
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
            .onFailure { out = ""; err = if (it is ApiError && it.code == 404) str(R.string.job_no_output) else it.message }
    }
    val j = job
    val runs = j?.optJSONArray("runs").strs()
    Column(Modifier.fillMaxSize()) {
        TopBar(j?.str("name")?.ifBlank { null } ?: id, j?.let { it.str("schedule_display").ifBlank { it.str("schedule") } }, contentTitle = true, contentSub = true) {
            IconBtn(Ic.edit, p.tx2) { Ui.push(Route.CronEditR(id)) }
        }
        LazyColumn(Modifier.weight(1f)) {
            if (j != null) item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    KV(stringResource(R.string.job_kv_status), if (j.optBoolean("enabled", true)) stringResource(R.string.job_status_active)
                        else j.str("paused_reason").takeIf { it.isNotBlank() }?.let { stringResource(R.string.job_status_paused_reason, bidiIsolate(it)) } ?: stringResource(R.string.job_status_paused))
                    parseTime(j.opt("next_run_at"))?.let { KV(stringResource(R.string.job_kv_next), until(it)) }
                    parseTime(j.opt("last_run_at"))?.let { KV(stringResource(R.string.job_kv_last), stringResource(R.string.job_last_value, rel(it), bidiIsolate(j.str("last_status").ifBlank { "ok" }))) }
                    if (j.str("model").isNotBlank()) KV(stringResource(R.string.job_kv_model), bidiIsolate(j.str("model")))
                    if (j.str("deliver").isNotBlank()) KV(stringResource(R.string.job_kv_deliver), bidiIsolate(j.str("deliver")))
                    if (j.str("last_error").isNotBlank()) Text(j.str("last_error"), style = mono(12f, 500, p.bad).content())
                }
            }
            if (runs.isNotEmpty()) item {
                LazyRow(Modifier.padding(vertical = 4.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(runs) { r -> Chip(bidiIsolate(r.replace('_', ' ').take(16)), r == run) { run = r } }
                }
            }
            item {
                Box(Modifier.padding(16.dp)) {
                    val o = out
                    when {
                        o == null -> Skeleton(6)
                        o.isBlank() -> if (err != null) ErrorPanel(err!!, content = true) { retry++ } else Text(stringResource(R.string.job_empty_output), style = sans(14f, 400, p.mu))
                        else -> Md(o.take(40000))
                    }
                }
            }
            if (j != null && j.str("prompt").isNotBlank()) item {
                Section(stringResource(R.string.job_prompt), null)
                Text(j.str("prompt"), style = mono(12.5f, 400, p.tx2).content(), modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(30.dp))
            }
        }
    }
}

@Composable
fun KV(k: String, v: String) {
    Row {
        // The label column grows for a long translated label and always keeps a gap before the value.
        Text(k, style = mono(12.5f, 500, P.mu), modifier = Modifier.widthIn(min = 76.dp).padding(end = 10.dp))
        Text(v, style = mono(12.5f, 500, P.tx), modifier = Modifier.weight(1f))
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
        TopBar(stringResource(R.string.elsewhere_title), stringResource(R.string.elsewhere_subtitle))
        val l = list
        when {
            err != null -> ErrorPanel(err!!, content = true) { err = null; reloadKey++ }
            l == null -> Skeleton()
            l.isEmpty() -> Empty(stringResource(R.string.elsewhere_empty_title), stringResource(R.string.elsewhere_empty_sub))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(l, key = { it.str("id") }) { s ->
                    Row(Modifier.fillMaxWidth().clickable { Ui.push(Route.SessR(s.str("id"), s.str("title").ifBlank { str(R.string.elsewhere_untitled) }, s.str("source"))) }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Pill(s.str("source").ifBlank { "?" }, p.tx2, p.s3, content = true)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.str("title").ifBlank { stringResource(R.string.elsewhere_untitled_session) }, style = sans(15f, 550, p.tx).let { if (s.str("title").isNotBlank()) it.contentLine() else it },
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                            Text(bidiIsolate(s.str("model").substringAfterLast('/')) + (s.optDouble("cost", 0.0).takeIf { it > 0 }?.let { " · " + money(it) } ?: ""), style = mono(11.5f, 500, p.mu), maxLines = 1)
                        }
                        Text(rel(s.optDouble("started_at", 0.0)), style = mono(11.5f, 500, p.mu))
                    }
                }
                item { Spacer(Modifier.height(30.dp)) }
            }
        }
    }
}

internal fun liveMessage(j: JSONObject, sid: String): Msg {
    val role = j.str("role").ifBlank { "assistant" }
    val kind = when {
        role == "tool" || j.str("display_kind") in setOf("tool", "trace") -> "trace"
        role == "system" || j.str("display_kind") == "status" -> "status"
        else -> "text"
    }
    return Msg(
        id = j.str("id").ifBlank { "$sid:${j.optLong("position")}" }, chatId = sid,
        role = role, kind = kind,
        content = j.str("text").ifBlank { j.str("content") }, meta = j,
        ts = j.optDouble("timestamp", j.optDouble("ts", 0.0)), seq = j.optLong("position"))
}

internal fun mergeLiveMessages(old: List<Msg>, page: List<Msg>): List<Msg> =
    (old + page).associateBy { it.id }.values.sortedWith(compareBy<Msg> { it.seq }.thenBy { it.ts })

@Composable
fun SessionScreen(r: Route.SessR) {
    val p = P
    val scope = rememberCoroutineScope()
    val act = LocalAct.current
    var msgs by remember(r.id) { mutableStateOf<List<Msg>?>(null) }
    var prompts by remember(r.id) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var answered by remember(r.id) { mutableStateOf<Set<String>>(emptySet()) }
    var nextBefore by remember(r.id) { mutableStateOf<Long?>(null) }
    var running by remember(r.id) { mutableStateOf(false) }
    var generation by remember(r.id) { mutableStateOf(0L) }
    var readOnly by remember(r.id) { mutableStateOf(false) }
    var err by remember(r.id) { mutableStateOf<String?>(null) }
    var refreshing by remember(r.id) { mutableStateOf(false) }
    var loadingOlder by remember(r.id) { mutableStateOf(false) }
    var sending by remember(r.id) { mutableStateOf(false) }
    var positioned by remember(r.id) { mutableStateOf(false) }
    var promptBusy by remember(r.id) { mutableStateOf<String?>(null) }
    var text by rememberSaveable(r.id) { mutableStateOf("") }
    val list = rememberLazyListState()
    val base = "/v1/sessions/${Uri.encode(r.id)}"

    suspend fun load(older: Boolean = false) {
        if (older) {
            val before = nextBefore ?: return
            if (loadingOlder) return
            loadingOlder = true
        } else {
            if (refreshing) return
            refreshing = true
        }
        val anchorId = if (older) msgs?.getOrNull(list.firstVisibleItemIndex)?.id else null
        val anchorOffset = if (older) list.firstVisibleItemScrollOffset else 0
        val hadMessages = !msgs.isNullOrEmpty()
        try {
            val suffix = if (older) "?before=$nextBefore&limit=40" else "?limit=40&refresh=1"
            val o = Api.get("$base/messages$suffix")
            var page = o.optJSONArray("messages").objs().map { liveMessage(it, r.id) }
            if (!older && hadMessages) {
                val known = msgs.orEmpty().mapTo(HashSet()) { it.id }
                val additions = page.toMutableList()
                var gap = o
                // A refresh returns the newest page. If a burst pushed every known
                // row out of it, walk backward until the pages join our history.
                while (page.none { it.id in known } && !gap.isNull("nextBefore")) {
                    val before = gap.getLong("nextBefore")
                    gap = Api.get("$base/messages?before=$before&limit=100")
                    page = gap.optJSONArray("messages").objs().map { liveMessage(it, r.id) }
                    additions.addAll(0, page)
                }
                msgs = mergeLiveMessages(msgs.orEmpty(), additions)
            } else {
                msgs = mergeLiveMessages(if (older) page + msgs.orEmpty() else msgs.orEmpty(), if (older) emptyList() else page)
            }
            if (older || !hadMessages) {
                nextBefore = if (o.isNull("nextBefore")) null else o.optLong("nextBefore")
            }
            if (older && anchorId != null) {
                val anchor = msgs!!.indexOfFirst { it.id == anchorId }
                if (anchor >= 0) list.scrollToItem(anchor, anchorOffset)
            }
            running = o.optBoolean("running")
            generation = o.optLong("generation")
            readOnly = o.optBoolean("readOnly")
            prompts = o.optJSONArray("prompts").objs().filter { it.str("state") !in setOf("answered", "denied", "cancelled", "interrupted") }
            err = null
        } catch (e: Exception) {
            err = e.message ?: str(R.string.session_load_failed)
        } finally {
            if (older) loadingOlder = false else refreshing = false
        }
    }

    fun send() {
        val value = text.trim()
        if (value.isEmpty() || sending || readOnly) return
        sending = true
        scope.launch {
            try {
                Api.put("$base/messages", JSONObject().put("text", value)
                    .put("submission_id", java.util.UUID.randomUUID().toString()))
                text = ""
                load()
                if (msgs.orEmpty().isNotEmpty()) list.animateScrollToItem(msgs!!.lastIndex)
            } catch (e: Exception) { Ui.say(e.message) } finally { sending = false }
        }
    }

    fun answerClarify(prompt: JSONObject, answer: String, questionId: String = "") {
        if (answer.isBlank() || promptBusy != null) return
        val pid = prompt.str("prompt_id")
        val key = "$pid:$questionId"
        promptBusy = key
        scope.launch {
            try {
                Api.put("$base/prompts/${Uri.encode(pid)}", JSONObject().put("kind", "clarify")
                    .put("generation", prompt.optLong("generation")).put("answer", answer)
                    .put("question_id", questionId))
                answered = answered + key
                load()
            } catch (e: Exception) { Ui.say(e.message) } finally { promptBusy = null }
        }
    }

    LaunchedEffect(r.id) { load() }
    VisibleEffect(true) {
        while (true) { delay(5000); if (!sending) load() }
    }
    LaunchedEffect(r.id) {
        Hub.sessionEvents.conflate().collect { event ->
            if (event.str("session_id") == r.id) { delay(120); load() }
        }
    }
    LaunchedEffect(msgs?.size) {
        val count = msgs?.size ?: return@LaunchedEffect
        if (!positioned) {
            list.scrollToItem(count - 1)
            positioned = true
        } else if (!loadingOlder && !list.canScrollForward) {
            list.animateScrollToItem(count - 1)
        }
    }
    LaunchedEffect(list, msgs?.size) {
        snapshotFlow { list.firstVisibleItemIndex }.distinctUntilChanged().collectLatest { index ->
            if (positioned && index == 0 && nextBefore != null) load(older = true)
        }
    }
    DisposableEffect(r.id) { onDispose { Hub.scope.launch { runCatching { Api.delete("$base/viewer") } } } }
    VoicePlaybackScope()

    Column(Modifier.fillMaxSize().background(p.bg)) {
        TopBar(r.title, when {
            readOnly -> stringResource(R.string.session_sub_read_only, bidiIsolate(r.source))
            running -> stringResource(R.string.session_sub_working, bidiIsolate(r.source))
            else -> stringResource(R.string.session_sub_live, bidiIsolate(r.source))
        }, right = {
            Icn(Ic.refresh, p.tx2, 20.dp, Modifier.clip(RoundedCornerShape(8.dp))
                .clickable { scope.launch { load() } }.padding(7.dp))
        })
        val current = msgs
        when {
            current == null && err != null -> ErrorPanel(err!!, content = true) { scope.launch { load() } }
            current == null -> Skeleton(6)
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
                contentPadding = PaddingValues(vertical = 10.dp)) {
                if (loadingOlder) item(key = "older") {
                    Text(stringResource(R.string.session_loading_older), style = mono(12f, 500, p.mu),
                        modifier = Modifier.fillMaxWidth().padding(16.dp))
                }
                if (current.isEmpty()) item(key = "empty") {
                    Empty(stringResource(R.string.session_empty_title), stringResource(R.string.session_empty_sub))
                }
                items(current, key = { it.id }) { m -> MsgView(m, emptyMap(), compact = false) }
                items(prompts, key = { it.str("prompt_id") }) { prompt ->
                    SessionPromptCard(prompt, answered, promptBusy,
                        onApproval = { choice ->
                            val pid = prompt.str("prompt_id")
                            promptBusy = pid
                            Approvals.runSession(act, r.id, prompt, choice) { error ->
                                promptBusy = null
                                if (error == null) { answered = answered + pid; scope.launch { load() } }
                                else if (error.isNotEmpty()) Ui.say(error)
                            }
                        }, onClarify = ::answerClarify)
                }
                item(key = "bottom") { Spacer(Modifier.height(8.dp)) }
            }
        }
        if (err != null && current != null) Text(err!!, style = sans(12.5f, 400, p.bad).content(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 3.dp))
        if (running) Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LiveDot(7.dp); Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.session_working), style = sans(12.5f, 400, p.acct), modifier = Modifier.weight(1f))
            if (text.isBlank()) Icn(Ic.stop, p.bad, 18.dp, Modifier.clip(RoundedCornerShape(7.dp)).clickable {
                scope.launch { runCatching { Api.post("$base/stop", JSONObject().put("generation", generation)); load() }
                    .onFailure { Ui.say(it.message) } }
            }.padding(7.dp))
        }
        if (readOnly) Text(stringResource(R.string.session_superseded),
            style = sans(13f, 400, p.mu), modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        else Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
            InputBox(text, { text = it }, if (running) stringResource(R.string.session_input_steer) else stringResource(R.string.session_input_message), Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            IconBtn(Ic.send, p.ink, 46.dp, p.acc, enabled = text.isNotBlank() && !sending) { send() }
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val p = P
    Text(label, style = sans(13.5f, 550, if (selected) p.acct else p.tx2).content(), maxLines = 2,
        modifier = Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) p.accd else p.s2)
            .border(1.dp, if (selected) p.acct else p.line, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
private fun SessionPromptCard(prompt: JSONObject, answered: Set<String>, busy: String?,
                              onApproval: (String) -> Unit,
                              onClarify: (JSONObject, String, String) -> Unit) {
    val p = P
    val pid = prompt.str("prompt_id")
    val approval = pid.isNotBlank() && (prompt.has("command") || prompt.has("options") && !prompt.has("question") && !prompt.has("questions"))
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
        .clip(RoundedCornerShape(18.dp)).background(p.s1).border(1.dp, p.att.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
        .padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icn(if (approval) Ic.shield else Ic.blocks, p.att, 18.dp)
            Spacer(Modifier.width(9.dp))
            Text(if (approval) stringResource(R.string.session_approval_needed) else stringResource(R.string.session_agent_asking, bidiIsolate(HERMES)), style = sans(15f, 650, p.tx))
        }
        if (approval) {
            if (prompt.str("command").isNotBlank()) Text(prompt.str("command"), style = mono(12.5f, 500, p.tx2).ltr())
            if (prompt.str("detail").isNotBlank()) Text(prompt.str("detail"), style = sans(13.5f, 400, p.mu, 19f).content())
            if (answered.contains(pid)) Text(stringResource(R.string.session_response_sent), style = sans(13f, 500, p.goodt))
            else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn(stringResource(R.string.session_allow_once), enabled = busy == null) { onApproval("once") }
                    if (!Prefs.noLock) Btn(stringResource(R.string.session_allow_session), primary = false, enabled = busy == null) { onApproval("session") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!Prefs.noLock) Btn(stringResource(R.string.session_allow_always), primary = false, enabled = busy == null) { onApproval("always") }
                    Btn(stringResource(R.string.common_deny), primary = false, enabled = busy == null) { onApproval("deny") }
                }
            }
        } else {
            val questions = prompt.optJSONArray("questions").objs().ifEmpty { listOf(prompt) }
            for (q in questions) {
                val qid = q.str("qid")
                val key = "$pid:$qid"
                val options = (q.optJSONArray("options") ?: q.optJSONArray("choices"))?.let { a ->
                    (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
                }.orEmpty()
                val multi = q.optBoolean("multi_select")
                var draft by remember(key) { mutableStateOf("") }
                var selected by remember(key) { mutableStateOf<Set<Int>>(emptySet()) }
                Text(q.str("question").ifBlank { stringResource(R.string.session_your_answer) }, style = sans(14.5f, 550, p.tx, 20f).let { if (q.str("question").isNotBlank()) it.content() else it })
                if (answered.contains(key)) Text(stringResource(R.string.session_answer_sent), style = sans(13f, 500, p.goodt))
                else {
                    if (options.isNotEmpty()) {
                        for ((index, option) in options.withIndex()) {
                            ChoiceChip(if (multi && index in selected) "✓ $option" else option,
                                selected = multi && index in selected, enabled = busy == null) {
                                if (multi) selected = if (index in selected) selected - index else selected + index
                                else onClarify(prompt, option, qid)
                            }
                        }
                        if (multi) Btn(stringResource(R.string.session_send_selection), primary = false, enabled = busy == null && selected.isNotEmpty()) {
                            onClarify(prompt, selected.sorted().joinToString(",") { (it + 1).toString() }, qid)
                        }
                    }
                    InputBox(draft, { draft = it }, if (options.isEmpty()) stringResource(R.string.session_type_answer) else stringResource(R.string.session_other_detail), Modifier.fillMaxWidth())
                    if (draft.isNotBlank()) Btn(stringResource(R.string.session_send_answer), enabled = busy == null) {
                        onClarify(prompt, draft.trim(), qid); draft = ""
                    }
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
    val mo by Momentum.state.collectAsState()
    val moRead by Momentum.read.collectAsState()
    LaunchedEffect(Unit) { runCatching { Hub.refreshMe() }; runCatching { Hub.health() } }
    // Once each time Me shows (the app coming back counts too), so the Radar badge is current; no polling.
    VisibleEffect(feat.radar) { if (feat.radar) runCatching { Momentum.load() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        item { Header(stringResource(R.string.me_title)) }
        item {
            Panel {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    MarkBox(44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(meta.str("name").ifBlank { Prefs.server ?: "Hermes" }, style = sans(17f, 650, p.tx).content())
                        Text(stringResource(R.string.me_profile_model, bidiIsolate(meta.str("profile").ifBlank { Prefs.profile ?: "default" }), bidiIsolate(meta.str("model").ifBlank { "–" })), style = mono(12f, 500, p.mu), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (online) p.good else p.dim, 8.dp); Spacer(Modifier.width(6.dp))
                        Text(if (online) stringResource(R.string.me_status_live) else stringResource(R.string.me_status_offline), style = mono(12f, 600, if (online) p.goodt else p.mu))
                    }
                }
            }
        }
        item { RestartBanner() }
        if (degraded.isNotEmpty()) item {
            Panel { NavRow(Ic.server, stringResource(R.string.me_server_problems), pluralStringResource(R.plurals.me_server_problems_count, degraded.size, degraded.size), p.bad) { probs = true } }
        }
        item { Section(stringResource(R.string.common_settings), null) }
        item {
            Panel {
                NavRow(Ic.sliders, stringResource(R.string.me_agent), stringResource(R.string.me_agent_sub)) { Ui.push(Route.SettingsR) }
                Line()
                NavRow(Ic.phone, stringResource(R.string.me_this_phone), stringResource(R.string.me_this_phone_sub)) { Ui.push(Route.PhoneR) }
            }
        }
        item { Section(stringResource(R.string.me_workspace), null) }
        item {
            Panel {
                NavRow(Ic.download, stringResource(R.string.me_hub), stringResource(R.string.me_hub_sub)) { Ui.push(Route.HubR) }
                if (feat.radar) {
                    Line()
                    val rep = Momentum.report(mo)
                    val needs = Momentum.count(rep, "needs_you")
                    val fresh = Momentum.unread(rep, "needs_you", moRead).size
                    val sub = when {
                        mo?.optBoolean("available") != true -> stringResource(R.string.me_radar_sub_scout)
                        mo?.optBoolean("scout", true) == false -> stringResource(R.string.me_radar_sub_momentum)
                        else -> stringResource(R.string.me_radar_sub_both)
                    }
                    NavRow(Ic.zap, stringResource(R.string.me_radar), sub,
                        badge = if (needs > 99) "99+" else if (needs > 0) "$needs" else null,
                        badgeHot = fresh > 0,
                        badgeLabel = if (fresh > 0) pluralStringResource(R.plurals.me_radar_decisions_new_desc, needs, needs, fresh)
                            else pluralStringResource(R.plurals.me_radar_decisions_desc, needs, needs)) { Ui.push(Route.RadarR) }
                }
                Line()
                NavRow(Ic.blocks, stringResource(R.string.me_skills), stringResource(R.string.me_skills_sub)) { Ui.push(Route.ExtR) }
                Line()
                NavRow(Ic.book, stringResource(R.string.me_memory), stringResource(R.string.me_memory_sub, bidiIsolate(agentName()))) { Ui.push(Route.MemoryR) }
                Line()
                NavRow(Ic.chart, stringResource(R.string.me_usage), stringResource(R.string.me_usage_sub)) { Ui.push(Route.UsageR) }
                if (feat.projects) {
                    Line()
                    NavRow(Ic.file, stringResource(R.string.me_projects), stringResource(R.string.me_projects_sub)) { Ui.push(Route.ProjectsR) }
                }
                Line()
                NavRow(Ic.search, stringResource(R.string.me_search), stringResource(R.string.me_search_sub)) { Ui.push(Route.SearchR) }
                Line()
                NavRow(Ic.power, stringResource(R.string.me_restart, bidiIsolate(HERMES)), stringResource(R.string.me_restart_sub)) { restart = true }
            }
        }
        item { MachinesSection() }
        item { Panel { NavRow(Ic.shield, stringResource(R.string.me_privacy), stringResource(R.string.me_privacy_sub)) { Ui.push(Route.PrivacyR) } } }
        item { Panel { NavRow(Ic.book, stringResource(R.string.me_licenses), stringResource(R.string.me_licenses_sub)) { Ui.push(Route.LegalR()) } } }
        editionHelpItems()
        item { ReportProblemRow() }
        item {
            Text("$EDITION_NAME " + runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty(),
                style = mono(11.5f, 500, p.dim), modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp))
        }
    }
    if (restart) RestartDialog { restart = false }
    if (probs) HDialog(stringResource(R.string.me_server_problems), onDismiss = { probs = false }, body = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                degraded.forEach { d ->
                    Text(bidiJoin(listOf(d.str("path"), d.optInt("status", 0).takeIf { it > 0 }?.toString().orEmpty(), d.str("error"))
                        .filter { it.isNotBlank() }), style = mono(12f, 500, p.tx2))
                }
            }
        }) {
            Btn(stringResource(R.string.common_close)) { probs = false }
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
    var unpair by remember { mutableStateOf(false) }
    var push by remember { mutableStateOf(Prefs.fcmCfg != null) }
    suspend fun loadDevices() { runCatching { val o = Api.get("/v1/devices"); devices = o.optJSONArray("devices").objs(); thisId = o.str("this") } }
    LaunchedEffect(Unit) { loadDevices(); push = runCatching { Push.refresh(ctx) }.getOrDefault(push) }
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.phone_settings_title), stringResource(R.string.phone_settings_subtitle))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Seg(phoneSettingsTabs(), tab) { tab = it }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp)) {
            when (tab) {
                "control" -> channelControlSettings()
                "look" -> {
                item { Section(stringResource(R.string.phone_settings_theme), null) }
                item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Seg(listOf("0" to stringResource(R.string.phone_settings_theme_system), "1" to stringResource(R.string.phone_settings_theme_night),
                            "2" to stringResource(R.string.phone_settings_theme_day)), Ui.theme.intValue.toString()) { Ui.theme.intValue = it.toInt(); Prefs.theme = it.toInt() }
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                            Accents.forEachIndexed { i, (name, night, _) ->
                                val sel = Ui.accent.intValue == i
                                val desc = stringResource(R.string.phone_settings_accent_desc, stringResource(name))
                                Box(Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                    .semantics { contentDescription = desc }
                                    .selectable(selected = sel, role = Role.RadioButton, onClick = { Ui.accent.intValue = i; Prefs.accent = i }), contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(if (sel) 34.dp else 28.dp).clip(androidx.compose.foundation.shape.CircleShape).background(night)
                                        .border(if (sel) 3.dp else 0.dp, p.tx, androidx.compose.foundation.shape.CircleShape))
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(Accents.getOrNull(Ui.accent.intValue)?.first?.let { stringResource(it) } ?: "", style = sans(14f, 500, p.tx2))
                        }
                    }
                }
                languageSettings()
                }
                "voice" -> {
                item { Section(stringResource(R.string.phone_settings_talk_voice), null) }
                item { Panel { TalkVoicePanel() } }
                item { Section(stringResource(R.string.phone_settings_wake_word), null) }
                item { WakeWordPanel() }
                }
                "alerts" -> {
                item { Section(stringResource(R.string.phone_settings_notifications), null) }
                item {
                    Panel {
                        ChannelConnectionSettings(push)
                        Line()
                        CronDeliveryRow()
                        Line()
                        SettingRow(stringResource(R.string.phone_settings_channels), stringResource(R.string.phone_settings_channels_sub)) {
                            Btn(stringResource(R.string.common_open), primary = false) { ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)) }
                        }
                        ChannelBatterySettings()
                    }
                }
                }
                else -> {
                // Revoked devices are history (test pairings, old phones); only the live ones matter here.
                val shown = devices.filter { it.optInt("revoked") == 0 }
                item { Section(stringResource(R.string.phone_settings_devices), Fmt.int(shown.size)) }
                item {
                    Panel {
                        shown.forEachIndexed { i, d ->
                            if (i > 0) Line()
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icn(Ic.phone, if (d.optInt("revoked") == 1) p.dim else p.tx2, 20.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(if (d.str("id") == thisId) stringResource(R.string.phone_settings_device_this, bidiIsolate(d.str("name"))) else bidiIsolate(d.str("name")), style = sans(15f, 550, if (d.optInt("revoked") == 1) p.dim else p.tx).short())
                                    Text(if (d.optInt("revoked") == 1) stringResource(R.string.phone_settings_device_revoked) else stringResource(R.string.phone_settings_device_seen, rel(d.optDouble("last_seen", 0.0))), style = mono(11.5f, 500, p.mu))
                                }
                                if (d.str("id") != thisId && d.optInt("revoked") == 0) Btn(stringResource(R.string.phone_settings_revoke), danger = true) {
                                    scope.launch { runCatching { Api.delete("/v1/devices/" + d.str("id")) }.onFailure { Ui.say(it.message) }; loadDevices() }
                                }
                            }
                        }
                    }
                }
                item { Section(stringResource(R.string.phone_settings_connection), null) }
                item {
                    Panel {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            KV(stringResource(R.string.phone_settings_kv_server), Prefs.base?.removePrefix("https://")?.let(::bidiIsolate) ?: "–")
                            KV(stringResource(R.string.phone_settings_kv_security), if (Prefs.cfId != null) stringResource(R.string.phone_settings_security_access) else stringResource(R.string.phone_settings_security_signed))
                            KV(stringResource(R.string.phone_settings_kv_approval), if (Prefs.noLock) stringResource(R.string.phone_settings_approval_no_lock) else stringResource(R.string.phone_settings_approval_biometric))
                        }
                    }
                }
                    item {
                        Column(Modifier.padding(20.dp)) {
                            Btn(stringResource(R.string.phone_settings_unpair_button), Modifier.fillMaxWidth(), danger = true) { unpair = true }
                            Spacer(Modifier.height(18.dp))
                            Text("$EDITION_NAME " + runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty(), style = mono(11.5f, 500, p.dim))
                        }
                    }
                }
            }
        }
    }
    if (unpair) HDialog(stringResource(R.string.phone_settings_unpair_title), onDismiss = { unpair = false }, body = {
            Text(stringResource(R.string.phone_settings_unpair_body), style = sans(15f, 400, p.tx2))
        }) {
            Btn(stringResource(R.string.common_cancel), primary = false) { unpair = false }
            Btn(stringResource(R.string.phone_settings_unpair), danger = true) {
            unpair = false
            scope.launch {
                runCatching { Prefs.deviceId?.let { Api.delete("/v1/devices/$it") } }
                PhoneService.disarm(ctx)
                LiveService.stop(ctx)
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
            Text(title, style = sans(15f, 600, P.tx).short())
            Text(sub, style = sans(13f, 400, P.mu, 18f).short())
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
        if (ok) apply(true) else Ui.say(R.string.phone_settings_wake_needs_mic)
    }
    Panel {
        SettingRow(stringResource(R.string.phone_settings_wake_say, bidiIsolate(WakeWord.label(Prefs.wakePhrase))), when {
            st.startsWith("error:") -> st.removePrefix("error:")
            !on -> stringResource(R.string.phone_settings_wake_off_sub)
            st == "paused" -> stringResource(R.string.phone_settings_wake_paused)
            st == "listening" -> if (hits > 0) pluralStringResource(R.plurals.phone_settings_wake_listening_hits, hits, hits) else stringResource(R.string.phone_settings_wake_listening)
            else -> stringResource(R.string.phone_settings_wake_starting)
        }) {
            Toggle(on, { v ->
                if (v && androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    perm.launch(android.Manifest.permission.RECORD_AUDIO) else apply(v)
            })
        }
        if (on) {
            Line()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.phone_settings_wake_engine, bidiIsolate("microWakeWord v2")), style = sans(15f, 600, p.tx))
                Text(pluralStringResource(R.plurals.phone_settings_wake_cutoff, 5, Fmt.decimal(thr.toDouble(), 2), 5), style = mono(12f, 500, p.mu))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Manual native-cutoff adjustment, not invented calibrated sensitivity presets.
                    Btn(stringResource(R.string.phone_settings_wake_lower), primary = false, enabled = thr > 0.01f) {
                        thr = (kotlin.math.round(thr * 100).toInt() - 1).coerceAtLeast(1) / 100f; Prefs.wakeThreshold = thr
                    }
                    Btn(stringResource(R.string.phone_settings_wake_higher), primary = false, enabled = thr < 0.99f) {
                        thr = (kotlin.math.round(thr * 100).toInt() + 1).coerceAtMost(99) / 100f; Prefs.wakeThreshold = thr
                    }
                }
                Btn(stringResource(R.string.phone_settings_wake_use_recommended, Fmt.decimal(WakeWord.DEFAULT_CUTOFF.toDouble(), 2)), primary = false) { thr = WakeWord.DEFAULT_CUTOFF; Prefs.wakeThreshold = thr }
                if (st == "listening") {
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.s3)) {
                        Box(Modifier.fillMaxWidth((activation / 255f).coerceIn(0f, 1f)).height(6.dp)
                            .background(p.acct.copy(alpha = 0.6f)))
                    }
                    Text(if (warmingUp) stringResource(R.string.phone_settings_wake_warming_up) else stringResource(R.string.phone_settings_wake_activation, kotlin.math.round(activation).toInt(), 255),
                        style = mono(12f, 500, p.mu))
                }
                Text(stringResource(R.string.phone_settings_wake_explainer), style = sans(12.5f, 400, p.mu, 17f))
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
            Text(stringResource(R.string.phone_settings_daystrip_next_24h), style = mono(11f, 700, p.mu), modifier = Modifier.weight(1f))
            Text(pluralStringResource(R.plurals.phone_settings_daystrip_runs, inDay.size, inDay.size), style = mono(11f, 500, p.tx2))
        }
        Spacer(Modifier.height(8.dp))
        val acc = p.acc; val tick = p.line2; val track = p.s2; val nowC = p.tx
        androidx.compose.foundation.Canvas(Modifier.mirrorInRtl().fillMaxWidth().height(28.dp).clip(RoundedCornerShape(14.dp)).background(track)) {
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
            listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEachIndexed { i, f ->
                Text(if (i == 0) stringResource(R.string.time_now) else Fmt.time(((now + f * 86400) * 1000).toLong()), style = mono(10f, 500, p.mu),
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
    SettingRow(stringResource(R.string.phone_settings_server_voice), if (server) stringResource(R.string.phone_settings_server_voice_on, bidiIsolate(HERMES)) else stringResource(R.string.phone_settings_server_voice_off)) {
        Toggle(server, { v -> server = v; Prefs.serverVoice = v })
    }
}
