package dev.avifenesh.hermes

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.json.JSONObject

// ---------------------------------------------------------------- activity card (in chat)
fun durStr(sec: Double): String = when {
    sec < 1 -> "<1s"
    sec < 60 -> "${sec.toInt()}s"
    sec < 3600 -> "${(sec / 60).toInt()}m ${(sec % 60).toInt()}s"
    else -> "${(sec / 3600).toInt()}h ${((sec % 3600) / 60).toInt()}m"
}

@Composable
fun ActivityCard(m: Msg) {
    val p = P
    val meta = m.meta
    val running = meta.str("running")
    val live = running.isNotBlank()
    val count = meta.optInt("count", 0)
    val errors = meta.optInt("errors", 0)
    val added = meta.optInt("added", 0)
    val removed = meta.optInt("removed", 0)
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).clip(RoundedCornerShape(18.dp))
        .background(if (live) p.accd else p.s1).border(1.dp, if (live) p.acc.copy(alpha = 0.35f) else p.line, RoundedCornerShape(18.dp))
        .clickable { Ui.push(Route.ActR(m.id, m.chatId)) }.animateContentSize().padding(horizontal = 14.dp, vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (live) LiveDot(8.dp) else Icn(Ic.tool, p.mu, 15.dp)
            Spacer(Modifier.width(9.dp))
            Text(m.content.ifBlank { "Tool activity" }, style = sans(14.5f, 600, p.tx), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Icn(Ic.chevR, p.mu, 16.dp)
        }
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!m.content.any { it.isDigit() }) Text("$count tool${if (count == 1) "" else "s"}", style = mono(12f, 500, p.mu))   // the summary usually carries the count already
            if (added + removed > 0) Text(buildAnnotatedString {
                withStyle(SpanStyle(color = p.good)) { append("+$added") }
                append(" ")
                withStyle(SpanStyle(color = p.bad)) { append("-$removed") }
            }, style = mono(12f, 600, p.mu))
            if (errors > 0) Text("$errors failed", style = mono(12f, 600, p.bad))
            val started = meta.optDouble("started", 0.0)
            val ended = meta.optDouble("ended", 0.0)
            if (!live && started > 0 && ended > started) Text(durStr(ended - started), style = mono(12f, 500, p.dim))
        }
        if (live) {
            Spacer(Modifier.height(4.dp))
            Text(running, style = mono(12f, 500, p.acct), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- activity detail screen
@Composable
fun ActivityScreen(mid: String, cid: String) {
    val p = P
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val all by Hub.msgs.collectAsState()
    val msg = all[cid]?.firstOrNull { it.id == mid }
    val live = msg?.meta?.str("running")?.isNotBlank() == true
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(mid, msg?.seq, retry) { err = null; runCatching { data = Api.get("/v1/activity/$mid") }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; err = it.message } }
    val calls = data?.optJSONArray("calls").objs()
    val files = data?.optJSONArray("files").objs()
    var tab by remember { mutableStateOf("calls") }
    val open = remember { mutableStateMapOf<String, Boolean>() }
    Column(Modifier.fillMaxSize()) {
        TopBar(msg?.content ?: "Tool activity", if (live) "working" else msg?.let { clock(it.ts) }, if (live) p.acct else p.mu)
        if (files.isNotEmpty()) Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Seg(listOf("calls" to "Calls · ${calls.size}", "files" to "Files · ${files.size}"), tab) { tab = it }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            if (err != null) item { ErrorPanel("Could not load tool activity: $err") { retry++ } }
            else if (data == null) item { Skeleton() }
            else if (calls.isEmpty() && files.isEmpty()) item { Empty("No tool activity", "Tool calls and changed files will appear here.") }
            if (tab == "files" && files.isNotEmpty()) {
                items(files.size) { i -> FileDiff(files[i]) }
            } else {
                items(calls.size) { i ->
                    val c = calls[i]
                    val id = c.str("id")
                    CallRow(c, open[id] == true) { open[id] = open[id] != true }
                }
            }
        }
    }
}

@Composable
private fun CallRow(c: JSONObject, expanded: Boolean, toggle: () -> Unit) {
    val p = P
    val st = c.str("status")
    val color = when (st) { "running" -> p.acct; "error" -> p.bad; "cancelled" -> p.att; else -> p.good }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(p.s1)
        .border(1.dp, p.line, RoundedCornerShape(14.dp)).animateContentSize()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (st == "running") LiveDot(8.dp) else Dot(color, 8.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(c.str("label").ifBlank { c.str("tool") }, style = mono(13f, 500, p.tx), maxLines = if (expanded) 6 else 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(c.str("tool"), style = mono(11f, 500, p.mu))
                    val ms = c.optLong("ms", 0)
                    if (ms > 0) Text(if (ms < 1000) "${ms}ms" else durStr(ms / 1000.0), style = mono(11f, 500, p.dim))
                    val a = c.optInt("added", 0); val r = c.optInt("removed", 0)
                    if (a + r > 0) Text("+$a -$r", style = mono(11f, 600, p.mu))
                }
            }
            Icn(if (expanded) Ic.chevU else Ic.chevD, p.mu, 16.dp)
        }
        if (expanded) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            c.str("error").takeIf { it.isNotBlank() }?.let { Text(it, style = mono(12f, 500, p.bad)) }
            c.str("detail").takeIf { it.isNotBlank() }?.let { Pane(if (c.str("tool") == "terminal") "command" else "input", it) }
            c.str("diff").takeIf { it.isNotBlank() }?.let { DiffPane(it) }
            c.str("output").takeIf { it.isNotBlank() }?.let { Pane("output", it) }
        }
    }
}

@Composable
private fun Pane(label: String, text: String) {
    val p = P
    val cb = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.s2)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = mono(10.5f, 600, p.mu))
            Spacer(Modifier.weight(1f))
            Text("copy", style = mono(10.5f, 500, p.mu), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { cb.setText(AnnotatedString(text)); Ui.say("Copied") }.padding(8.dp))
        }
        SelectionContainer {
            Text(text, style = mono(12f, 400, p.tx), softWrap = false,
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp))
        }
    }
}

@Composable
fun FileDiff(f: JSONObject) {
    val p = P
    var open by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(p.s1)
        .border(1.dp, p.line, RoundedCornerShape(14.dp))) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icn(Ic.file, p.tx2, 16.dp)
            Spacer(Modifier.width(8.dp))
            val path = f.str("path")
            Column(Modifier.weight(1f)) {
                Text(path.substringAfterLast('/'), style = mono(13f, 600, p.tx), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(path.substringBeforeLast('/', ""), style = mono(10.5f, 400, p.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = p.good)) { append("+${f.optInt("added", 0)}") }
                append(" ")
                withStyle(SpanStyle(color = p.bad)) { append("-${f.optInt("removed", 0)}") }
            }, style = mono(12f, 600, p.mu))
            Spacer(Modifier.width(6.dp))
            Icn(if (open) Ic.chevU else Ic.chevD, p.mu, 16.dp)
        }
        if (open) {
            val d = f.str("diff")
            Box(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp)) {
                if (d.isBlank()) Text("No diff captured for this file.", style = sans(13f, 400, p.mu), modifier = Modifier.padding(6.dp)) else DiffPane(d)
            }
        }
    }
}

@Composable
fun DiffPane(diff: String) {
    val p = P
    val lines = remember(diff) { diff.trimEnd('\n').lines().filterNot { it.startsWith("--- ") || it.startsWith("+++ ") } }
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.s2)) {
    val paneW = maxWidth
    Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
        .widthIn(min = paneW).width(IntrinsicSize.Max).padding(vertical = 6.dp)) {
        for (ln in lines) {
            val (fg, bg) = when {
                ln.startsWith("@@") -> p.acct to Color.Transparent
                ln.startsWith("+") -> p.good to p.good.copy(alpha = 0.10f)
                ln.startsWith("-") -> p.bad to p.bad.copy(alpha = 0.10f)
                else -> p.tx2 to Color.Transparent
            }
            Text(ln.ifEmpty { " " }, style = mono(12f, 400, fg), softWrap = false, modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 10.dp, vertical = 1.dp))
        }
    }
    }
}
