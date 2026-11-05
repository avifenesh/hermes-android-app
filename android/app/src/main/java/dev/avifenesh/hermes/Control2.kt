@file:OptIn(ExperimentalMaterial3Api::class)

package dev.avifenesh.hermes

import android.net.Uri
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

// ---------------------------------------------------------------- chat menu (session commands)
@Composable
fun ChatMenu(cid: String, running: Boolean, onUndoText: (String) -> Unit) {
    val p = P
    val co = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val chats by Hub.chats.collectAsState()
    val title = chats[cid]?.title.orEmpty()
    fun run(action: String, arg: String = "", confirmed: Boolean = false) {
        co.launch {
            try {
                val o = Api.post("/v1/chats/${Uri.encode(cid)}/session", JSONObject().put("action", action).put("arg", arg).put("confirmed", confirmed))
                val reply = o.str("reply")
                when (action) {
                    "status", "context" -> info = reply
                    "undo" -> { o.str("text").takeIf { it.isNotBlank() }?.let(onUndoText); Ui.say(if (o.optBoolean("ok")) str(R.string.chatmenu_undone) else Hub.plain(reply).take(200), content = !o.optBoolean("ok")) }
                    "save" -> Ui.say(if (o.optBoolean("ok")) str(R.string.chatmenu_exported) else Hub.plain(reply).take(200), content = !o.optBoolean("ok"))
                    else -> Ui.say(Hub.plain(reply).lineSequence().firstOrNull()?.take(160) ?: str(R.string.common_done))
                }
                runCatching { Hub.loadChat(cid); Hub.loadUsage(cid) }
            } catch (e: Exception) { Ui.say(e.message) }
        }
    }
    Box {
        IconBtn(Ic.more, p.tx2) { open = true }
        DropdownMenu(open, { open = false }, containerColor = p.s2) {
            @Composable fun item(label: String, enabled: Boolean = true, on: () -> Unit) =
                DropdownMenuItem({ Text(label, style = sans(15f, 500, if (enabled) p.tx else p.dim)) }, { open = false; on() }, enabled = enabled)
            item(stringResource(R.string.chatmenu_retry), !running) { co.launch { runCatching { Hub.send(cid, "/retry") }.onFailure { Ui.say(it.message) } } }
            item(stringResource(R.string.chatmenu_undo), !running) { ask = "undo" }
            item(stringResource(R.string.chatmenu_compress), !running) { run("compress") }
            item(stringResource(R.string.chatmenu_new_session), !running) { ask = "new" }
            item(stringResource(R.string.common_rename)) { rename = true }
            item(stringResource(R.string.chatmenu_export)) { run("save") }
            item(stringResource(R.string.chatmenu_context)) { run("context") }
            item(stringResource(R.string.chatmenu_status)) { run("status") }
        }
    }
    ask?.let { a ->
        HDialog(if (a == "new") stringResource(R.string.chatmenu_new_title) else stringResource(R.string.chatmenu_undo_title), onDismiss = { ask = null }, body = {
                Text(if (a == "new") stringResource(R.string.chatmenu_new_body, bidiIsolate(agentName()))
                else stringResource(R.string.chatmenu_undo_body, bidiIsolate(agentName())),
                style = sans(15f, 400, p.tx2, 21f))
            }) {
                Btn(stringResource(R.string.common_cancel), primary = false) { ask = null }
                Btn(if (a == "new") stringResource(R.string.chatmenu_start_fresh) else stringResource(R.string.chatmenu_undo_action)) { ask = null; run(a, confirmed = true) }
            }
    }
    if (rename) {
        var t by remember { mutableStateOf(title) }
        HDialog(stringResource(R.string.common_rename), onDismiss = { rename = false }, body = {
                InputBox(t, { t = it }, stringResource(R.string.chatmenu_title_hint), Modifier.fillMaxWidth(), maxLines = 2)
            }) {
                Btn(stringResource(R.string.common_cancel), primary = false) { rename = false }
                Btn(stringResource(R.string.common_save)) { rename = false; if (t.isNotBlank()) run("title", t.trim()) }
            }
    }
    info?.let { txt -> InfoSheet(txt) { info = null } }
}

@Composable
fun InfoSheet(md: String, onDismiss: () -> Unit) {
    val p = P
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = p.s1,
        dragHandle = { Box(Modifier.padding(top = 10.dp).size(40.dp, 4.dp).clip(CircleShape).background(p.s4)) }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Md(md)
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------- search across all sessions
@Composable
fun SearchScreen() {
    val p = P
    var q by rememberSaveable { mutableStateOf("") }
    var res by remember { mutableStateOf<List<JSONObject>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(q) {
        val s = q.trim()
        if (s.length < 2) { res = null; return@LaunchedEffect }
        delay(350)
        busy = true
        runCatching { val o = Api.get("/v1/search?q=" + Uri.encode(s)); res = o.optJSONArray("results").objs(); err = o.str("error").ifBlank { null } }
            .onFailure { err = it.message }
        busy = false
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(stringResource(R.string.search_title), stringResource(R.string.search_subtitle))
        InputBox(q, { q = it }, stringResource(R.string.search_hint), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), maxLines = 1)
        LazyColumn(Modifier.weight(1f)) {
            val l = res
            when {
                err != null -> item { Text(err!!, style = sans(14f, 400, p.bad).content(), modifier = Modifier.padding(20.dp)) }
                q.trim().length < 2 -> item { Text(stringResource(R.string.search_intro), style = sans(14f, 400, p.mu, 20f), modifier = Modifier.padding(20.dp)) }
                l == null || busy && l.isEmpty() -> item { Text(stringResource(R.string.search_searching), style = mono(13f, 500, p.mu), modifier = Modifier.padding(20.dp)) }
                l.isEmpty() -> item { Empty(stringResource(R.string.search_nothing_found), stringResource(R.string.search_no_mentions, bidiIsolate(q.trim()))) }
                else -> items(l) { r -> SearchHit(r) }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

@Composable
fun SearchHit(r: JSONObject) {
    val p = P
    val chats by Hub.chats.collectAsState()
    val mobileChat = r.str("chat_id").takeIf { it.isNotBlank() && chats.containsKey(it) }
    val title = r.str("title").ifBlank { stringResource(R.string.search_untitled) }
    // The user's own hit reads "You: <excerpt>"; split the template around a marker to keep the bold hit spans.
    val youSaid = stringResource(R.string.search_you_said, "\u0000")
    val mine = r.str("role") == "user"
    Column(Modifier.fillMaxWidth().clickable {
        if (mobileChat != null) Ui.open(mobileChat) else Ui.push(Route.SessR(r.str("session_id"), title, r.str("source")))
    }.padding(horizontal = 20.dp, vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(r.str("source").ifBlank { "?" }, p.tx2, p.s3, content = true)
            Spacer(Modifier.width(8.dp))
            Text(title, style = sans(14.5f, 600, p.tx).contentLine(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(rel(r.optDouble("timestamp", 0.0)), style = mono(11.5f, 500, p.mu))
        }
        Spacer(Modifier.height(4.dp))
        // An excerpt of someone else's words is content; "You: <excerpt>" is UI copy with the excerpt isolated in it.
        Text(snippet(r.str("snippet"), if (mine) youSaid.substringBefore('\u0000') else "", if (mine) youSaid.substringAfter('\u0000', "") else ""),
            style = sans(13.5f, 400, p.tx2, 19f).let { if (mine) it else it.content() }, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** FTS5 snippets mark hits as >>>word<<<; render them bold in the accent color. */
@Composable
fun snippet(s: String, prefix: String, suffix: String = "") = buildAnnotatedString {
    val framed = prefix.isNotEmpty() || suffix.isNotEmpty()
    append(prefix)
    // Inside the template the excerpt keeps its own direction; bidiIsolate also balances any direction controls the
    // excerpt carries, so a stray PDI cannot close the isolate early. The >>> <<< markers are ASCII and survive it.
    var rest = s.replace("\n", " ").let { if (framed) bidiIsolate(it) else it }
    while (true) {
        val a = rest.indexOf(">>>"); if (a < 0) { append(rest); break }
        val b = rest.indexOf("<<<", a + 3); if (b < 0) { append(rest); break }
        append(rest.substring(0, a))
        withStyle(SpanStyle(color = P.acct, fontWeight = FontWeight.SemiBold)) { append(rest.substring(a + 3, b)) }
        rest = rest.substring(b + 3)
    }
    append(suffix)
}

// ---------------------------------------------------------------- usage
@Composable
fun UsageScreen() {
    val p = P
    var days by remember { mutableStateOf(30) }
    var d by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(days, retry) { d = null; err = null; runCatching { d = Api.get("/v1/usage?days=$days") }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; err = it.message } }
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.usage_title), stringResource(R.string.usage_subtitle))
        LazyColumn(Modifier.weight(1f)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Seg(listOf(7, 30, 90).map { n -> n.toString() to pluralStringResource(R.plurals.usage_days, n, n) }, days.toString()) { days = it.toInt() }
                }
            }
            val o = d
            if (o == null) item { if (err != null) ErrorPanel(stringResource(R.string.usage_load_failed, bidiIsolate(err.orEmpty()))) { retry++ } else Skeleton() }
            else {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Stat(stringResource(R.string.usage_stat_spent), money(o.optDouble("total", 0.0)), Modifier.weight(1f))
                        Stat(stringResource(R.string.usage_stat_sessions), Fmt.int(o.optInt("sessions")), Modifier.weight(1f))
                        val series = o.optJSONArray("series").objs()
                        Stat(stringResource(R.string.usage_stat_per_day), money(o.optDouble("total", 0.0) / series.size.coerceAtLeast(1)), Modifier.weight(1f))
                    }
                    val suspect = o.optJSONObject("suspect")?.takeIf { it.optInt("sessions") > 0 }
                    val included = o.optInt("included_sessions")
                    val unpriced = o.optInt("unpriced_sessions")
                    val notes = buildList {
                        suspect?.let { val n = it.optInt("sessions"); add(plural(R.plurals.usage_suspect_note, n, n, money(it.optDouble("cost", 0.0)))) }
                        if (included > 0) add(plural(R.plurals.usage_included_note, included, included))
                        if (unpriced > 0) add(plural(R.plurals.usage_unpriced_note, unpriced, unpriced))
                    }
                    for (n in notes) Text(n, style = sans(12.5f, 400, p.mu, 17f), modifier = Modifier.padding(horizontal = 20.dp, vertical = 3.dp))
                    DayBars(o.optJSONArray("series").objs())
                }
                val models = o.optJSONArray("models").objs()
                // Same family on several providers/regions ("global." vs "us." vs direct) must not read as one row.
                val labels = labelsFor(models.map { it.str("model") })
                val top = models.maxOfOrNull { it.optDouble("cost", 0.0) }?.coerceAtLeast(0.0001) ?: 1.0
                item { Section(stringResource(R.string.usage_by_model), Fmt.int(models.size)) }
                items(models) { m ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(labels[m.str("model")] ?: shortModel(m.str("model")), style = mono(13.5f, 600, p.tx).contentLine(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(12.dp))
                            Text(when { m.optBoolean("included") && m.optDouble("cost", 0.0) == 0.0 -> stringResource(R.string.usage_included); m.optInt("unpriced") >= m.optInt("sessions") && m.optDouble("cost", 0.0) == 0.0 -> stringResource(R.string.usage_na); else -> money(m.optDouble("cost", 0.0)) }, style = mono(13f, 600, p.tx2))
                        }
                        Spacer(Modifier.height(5.dp))
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.s3)) {
                            Box(Modifier.fillMaxWidth((m.optDouble("cost", 0.0) / top).toFloat().coerceIn(0.01f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.acct))
                        }
                        Spacer(Modifier.height(4.dp))
                        val sessions = m.optInt("sessions"); val calls = m.optInt("calls")
                        Facts(listOfNotNull(m.str("provider").ifBlank { null }?.let(::bidiIsolate), pluralStringResource(R.plurals.usage_sessions, sessions, sessions), pluralStringResource(R.plurals.usage_calls, calls, calls),
                            stringResource(R.string.usage_tokens, tok(m.optLong("tokens"))),
                            m.optDouble("cache_hit").takeIf { !it.isNaN() }?.let { stringResource(R.string.usage_cache_hit, Fmt.percent((it * 100).toInt() / 100.0)) }),
                            mono(11.5f, 500, p.mu))
                    }
                }
                val src = o.optJSONArray("sources").objs()
                if (src.isNotEmpty()) item {
                    Section(stringResource(R.string.usage_by_surface), null)
                    Panel {
                        src.forEachIndexed { i, s ->
                            if (i > 0) Line()
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(s.str("source"), style = sans(14.5f, 550, p.tx).contentLine(), modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(12.dp))
                                Text(Fmt.int(s.optInt("sessions")) + " · " + money(s.optDouble("cost", 0.0)), style = mono(12.5f, 500, p.tx2))
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

@Composable
fun DayBars(series: List<JSONObject>) {
    val p = P
    if (series.isEmpty()) return
    val v = series.map { it.optDouble("cost", 0.0) }
    val mx = (v.maxOrNull() ?: 0.0).coerceAtLeast(0.0001)
    // The server sends ISO days ("2026-11-04"); show the first one in the app language's day-month form.
    val first = series.first().str("day")
    val firstLabel = remember(first) {
        runCatching { Fmt.dayMonth(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(first)!!.time) }.getOrDefault(first.drop(5))
    }
    Panel(Modifier.padding(top = 8.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
            Text(firstLabel, style = mono(11f, 500, p.mu))
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.usage_max, money(v.max())), style = mono(11f, 500, p.mu))
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.usage_today), style = mono(11f, 500, p.mu))
        }
        Canvas(Modifier.fillMaxWidth().height(90.dp).padding(16.dp).mirrorInRtl()) {
            val w = size.width / v.size
            v.forEachIndexed { i, x ->
                val h = (x / mx).toFloat() * size.height
                drawRoundRect(if (i == v.lastIndex) p.acc else p.s4, Offset(i * w + w * 0.15f, size.height - h.coerceAtLeast(2f)),
                    Size(w * 0.7f, h.coerceAtLeast(2f)), CornerRadius(4f, 4f))
            }
        }
    }
}

// ---------------------------------------------------------------- memory editing
@Composable
fun MemoryEditor(target: String, entry: String?, onDone: () -> Unit) {
    val p = P
    val co = rememberCoroutineScope()
    var t by remember { mutableStateOf(entry.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf(false) }
    fun send(action: String, content: String = "") {
        busy = true
        co.launch {
            try {
                val b = JSONObject().put("target", target).put("action", action).put("content", content)
                if (entry != null) b.put("old_text", entry)
                val o = Api.post("/v1/memory", b)
                Ui.say(o.str("message").ifBlank { str(R.string.common_saved) }); onDone()
            } catch (e: Exception) { Ui.say(e.message) } finally { busy = false }
        }
    }
    HDialog(if (entry != null) stringResource(R.string.memory_edit_entry)
        else if (target == "user") stringResource(R.string.memory_add_to_about) else stringResource(R.string.memory_add_to_notes, bidiIsolate(agentName())), onDismiss = onDone, body = {
            Column {
                InputBox(t, { t = it }, stringResource(R.string.memory_entry_hint), Modifier.fillMaxWidth().heightIn(min = 90.dp), minLines = 3, maxLines = 8)
                Text(stringResource(R.string.memory_editor_note), style = sans(12.5f, 400, p.mu, 17f),
                    modifier = Modifier.padding(top = 8.dp))
                if (entry != null) TextButton({ confirmDel = true }, enabled = !busy, modifier = Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.memory_delete_entry), style = sans(14.5f, 600, p.bad)) }
            }
        }) {
            Btn(stringResource(R.string.common_cancel), primary = false, onClick = onDone)
            Btn(if (busy) stringResource(R.string.memory_saving) else stringResource(R.string.common_save), enabled = !busy && t.isNotBlank() && t.trim() != entry) { if (t.isNotBlank()) send(if (entry == null) "add" else "replace", t.trim()) }
        }
    if (confirmDel) HDialog(stringResource(R.string.memory_forget_title), onDismiss = { confirmDel = false }, body = {
            Text(entry.orEmpty().take(300), style = sans(14.5f, 400, p.tx2, 20f).content())
        }) {
            Btn(stringResource(R.string.memory_keep), primary = false) { confirmDel = false }
            Btn(stringResource(R.string.memory_forget), danger = true) { confirmDel = false; send("remove") }
        }
}

// ---------------------------------------------------------------- cron editor
@Composable
fun CronEditScreen(id: String?) {
    val p = P
    val co = rememberCoroutineScope()
    val label = modelLabeler()
    var loaded by rememberSaveable(id) { mutableStateOf(id == null) }
    var name by rememberSaveable(id) { mutableStateOf("") }
    var schedule by rememberSaveable(id) { mutableStateOf("") }
    var prompt by rememberSaveable(id) { mutableStateOf("") }
    var deliver by rememberSaveable(id) { mutableStateOf("mobile") }
    var skills by rememberSaveable(id) { mutableStateOf("") }
    var model by rememberSaveable(id, stateSaver = JsonSaver) { mutableStateOf(JSONObject()) }
    var reasoning by rememberSaveable(id) { mutableStateOf("") }
    var orig by rememberSaveable(id, stateSaver = JsonSaver) { mutableStateOf(JSONObject()) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var pickModel by remember { mutableStateOf(false) }
    var pickDeliver by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf(false) }
    var loadErr by remember(id) { mutableStateOf<String?>(null) }
    var retry by remember(id) { mutableStateOf(0) }
    LaunchedEffect(id, retry) {
        if (id == null || loaded) return@LaunchedEffect
        loadErr = null
        runCatching { Api.get("/v1/cronjob/" + Uri.encode(id)) }.onSuccess { j ->
            orig = j
            name = j.str("name"); schedule = j.str("schedule_expr").ifBlank { j.str("schedule") }; prompt = j.str("prompt")
            deliver = j.str("deliver").ifBlank { "local" }; skills = j.optJSONArray("skills").strs().joinToString(", ")
            model = JSONObject().put("model", j.str("model")).put("provider", j.str("provider")); reasoning = j.str("reasoning_effort")
            loaded = true
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; loadErr = it.message ?: str(R.string.common_connection_failed) }
    }
    fun save() {
        busy = true; err = null
        co.launch {
            try {
                val b = JSONObject().put("name", name.trim()).put("schedule", schedule.trim()).put("prompt", prompt.trim()).put("deliver", deliver)
                    .put("skills", org.json.JSONArray(skills.split(',').map { it.trim() }.filter { it.isNotBlank() }))
                    .put("model", model.str("model")).put("provider", model.str("provider")).put("reasoning_effort", reasoning)
                val o = if (id == null) Api.post("/v1/cronjob", b) else Api.put("/v1/cronjob/" + Uri.encode(id), b)
                if (o.optBoolean("success")) { if (id == null) Ui.say(R.string.cronedit_scheduled) else Ui.say(R.string.common_saved); Ui.pop() } else err = o.str("error")
            } catch (e: Exception) { err = e.message } finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(if (id == null) stringResource(R.string.cronedit_new_title) else stringResource(R.string.cronedit_edit_title),
            if (id == null) stringResource(R.string.cronedit_new_sub) else orig.str("schedule_display").ifBlank { null }, contentSub = id != null) {
            if (id != null) IconBtn(Ic.trash, p.bad) { confirmDel = true }
        }
        if (!loaded) {
            if (loadErr != null) ErrorPanel(stringResource(R.string.cronedit_load_failed, bidiIsolate(loadErr.orEmpty()))) { retry++ } else Skeleton()
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Field(stringResource(R.string.cronedit_name)) { InputBox(name, { name = it }, stringResource(R.string.cronedit_name_hint), Modifier.fillMaxWidth(), maxLines = 1) }
            // Schedule examples and chips are the server's schedule syntax (English); they stay as typed.
            Field(stringResource(R.string.cronedit_schedule), "30m · every 2h · weekdays at 9am · 0 9 * * * · in 45m (once)", ltrHint = true) {
                InputBox(schedule, { schedule = it }, "every day at 9am", Modifier.fillMaxWidth(), maxLines = 1, ltr = true)
                Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in listOf("every 1h", "every day at 9am", "weekdays at 9am", "every monday 9am", "0 */6 * * *")) Chip(bidiIsolate(s), schedule == s) { schedule = s }
                }
            }
            Field(stringResource(R.string.cronedit_prompt), stringResource(R.string.cronedit_prompt_note)) {
                InputBox(prompt, { prompt = it }, stringResource(R.string.cronedit_prompt_hint, bidiIsolate(agentName())), Modifier.fillMaxWidth().heightIn(min = 130.dp), minLines = 5, maxLines = 14)
            }
            Field(stringResource(R.string.cronedit_deliver_to)) {
                SettingPick(deliverLabel(deliver)) { pickDeliver = true }
            }
            Field(stringResource(R.string.cronedit_model), stringResource(R.string.cronedit_model_note)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val picked = model.str("model")
                    SettingPick(if (picked.isBlank()) stringResource(R.string.cronedit_model_default) else label(picked), Modifier.weight(1f), content = picked.isNotBlank()) { pickModel = true }
                    if (model.str("model").isNotBlank()) { Spacer(Modifier.width(8.dp)); Btn(stringResource(R.string.cronedit_clear), primary = false) { model = JSONObject() } }
                }
                Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(stringResource(R.string.cronedit_default_effort), reasoning.isBlank()) { reasoning = "" }
                    for (e in listOf("none", "low", "medium", "high", "xhigh")) Chip(bidiIsolate(e), reasoning == e) { reasoning = e }
                }
            }
            Field(stringResource(R.string.cronedit_skills), stringResource(R.string.cronedit_skills_note)) {
                InputBox(skills, { skills = it }, stringResource(R.string.cronedit_skills_hint, bidiIsolate("github-issues, grounded-citations")), Modifier.fillMaxWidth(), maxLines = 3)
            }
            err?.let { Text(Hub.plain(it), style = mono(12.5f, 500, p.bad).content(), modifier = Modifier.padding(vertical = 10.dp)) }
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            Btn(if (busy) stringResource(R.string.cronedit_saving) else if (id == null) stringResource(R.string.cronedit_schedule_job) else stringResource(R.string.cronedit_save_changes), Modifier.fillMaxWidth(),
                enabled = !busy && schedule.isNotBlank() && (prompt.isNotBlank() || skills.isNotBlank())) { save() }
        }
    }
    if (pickModel) ModelSheet(stringResource(R.string.cronedit_job_model), model, false, false, "global", {}, onModel = { m, pr -> model = JSONObject().put("model", m).put("provider", pr); pickModel = false },
        onReasoning = {}, onFast = null, onDismiss = { pickModel = false })
    if (pickDeliver) OptionSheet(stringResource(R.string.cronedit_deliver_to), listOf("mobile", "telegram,mobile", "telegram", "local"), deliver, onPick = { deliver = it; pickDeliver = false }, onDismiss = { pickDeliver = false })
    if (confirmDel) HDialog(stringResource(R.string.cronedit_delete_title), onDismiss = { confirmDel = false }, body = {
            Text(stringResource(R.string.cronedit_delete_body, bidiIsolate(name.ifBlank { id.orEmpty() })), style = sans(15f, 400, p.tx2, 21f))
        }) {
            Btn(stringResource(R.string.common_cancel), primary = false) { confirmDel = false }
            Btn(stringResource(R.string.common_delete), danger = true) {
            confirmDel = false
            co.launch { runCatching { Api.delete("/v1/cronjob/" + Uri.encode(id!!)) }.onSuccess { Ui.say(R.string.cronedit_deleted); Ui.stack.removeAll { it is Route.JobR && it.id == id }; Ui.pop() }.onFailure { Ui.say(it.message) } }
        }
        }
}

fun deliverLabel(d: String): String = when (d.replace(" ", "")) {
    "mobile", "mobile:digests", "origin,mobile:digests" -> str(R.string.cronedit_deliver_app)
    "telegram,mobile", "telegram,mobile:digests", "mobile,telegram" -> str(R.string.cronedit_deliver_telegram_app)
    "telegram" -> "Telegram"
    "local" -> str(R.string.cronedit_deliver_local)
    else -> d
}

/** [ltrHint]: the hint is syntax (schedule expressions), not a sentence, and reads left to right. */
@Composable
fun Field(label: String, hint: String? = null, ltrHint: Boolean = false, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(label.uppercase(L10n.locale()), style = mono(11f, 600, P.mu), modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        content()
        if (hint != null) Text(hint, style = sans(12f, 400, P.dim, 16f).let { if (ltrHint) it.ltr().alignToLayoutStart() else it }, modifier = Modifier.padding(start = 4.dp, top = 5.dp))
    }
}

/** [content]: [text] is a name from the gateway (a model) rather than UI copy. */
@Composable
fun SettingPick(text: String, modifier: Modifier = Modifier, content: Boolean = false, on: () -> Unit) {
    val p = P
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.s2).clickable(onClick = on).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = sans(15f, 500, p.tx).let { if (content) it.contentLine() else it }, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icn(Ic.chevD, p.mu, 16.dp)
    }
}
