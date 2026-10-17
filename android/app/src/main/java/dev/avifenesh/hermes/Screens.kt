@file:OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.avifenesh.hermes

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import org.json.JSONObject
import java.io.File

// ---------------------------------------------------------------- inbox
@Composable
fun InboxScreen() {
    val p = P
    val chats by Hub.chats.collectAsState()
    val work by Hub.working.collectAsState()
    val appr by Hub.approvals.collectAsState()
    val online by Hub.online.collectAsState()
    var filter by rememberSaveable { mutableStateOf("all") }
    var q by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val need = appr.values.filter { it.state == "pending" }.map { it.chatId }.toSet()
    val msgs by Hub.msgs.collectAsState()
    val list = chats.values.filter { c ->
        val f = when (filter) {
            "chats" -> c.kind != "digest" && !c.archived
            "digests" -> c.kind == "digest" && !c.archived
            "archived" -> c.archived
            else -> !c.archived
        }
        f && (q.isBlank() || c.title.contains(q, true) || c.preview.contains(q, true) ||
            msgs[c.id].orEmpty().any { it.content.contains(q, true) })
    }.sortedWith(compareByDescending<Chat> { it.id in need }.thenByDescending { it.id in work }.thenByDescending { it.pinned }.thenByDescending { it.updated })
    Column(Modifier.fillMaxSize()) {
        val me by Hub.meta.collectAsState()
        val metaLine = listOf("name", "profile", "model").map { me.str(it).trim() }.filter { it.isNotBlank() }.joinToString(" · ")
        Header("Inbox", if (online) null else "Offline · showing what's on the phone", meta = {
            Row(Modifier.padding(top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(if (online) p.good else p.mu, 7.dp)
                if (metaLine.isNotBlank()) { Spacer(Modifier.width(7.dp)); Text(metaLine, style = mono(11.5f, 500, p.mu), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }) {
            IconBtn(Ic.mic, p.acct) { Ui.push(Route.TalkR) }
            IconBtn(Ic.search, if (searching) p.acct else p.tx) { searching = !searching; if (!searching) q = "" }
        }
        if (searching) {
            InputBox(q, { q = it }, "Search messages", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), maxLines = 1)
            Text("Search every session on the machine →", style = sans(13.5f, 600, p.acct), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                .clickable { Ui.push(Route.SearchR) })
        }
        if (work.isNotEmpty()) {
            val n = work.size
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(20.dp)).background(p.s1)
                .border(1.dp, p.line, RoundedCornerShape(20.dp)).clickable { Ui.tab.value = "pulse" }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                LiveDot(8.dp); Spacer(Modifier.width(10.dp))
                Text("${agentName()} is on $n thing${if (n == 1) "" else "s"}", style = sans(14f, 600, p.tx), modifier = Modifier.weight(1f))
                Icn(Ic.chevR, p.mu, 16.dp)
            }
        }
        val unreadChats = chats.values.filter { it.kind != "digest" && !it.archived }.sumOf { it.unread }
        val unreadDigests = chats.values.filter { it.kind == "digest" && !it.archived }.sumOf { it.unread }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("All", filter == "all") { filter = "all" }
            Chip("Chats", filter == "chats", unreadChats) { filter = "chats" }
            Chip("Digests", filter == "digests", unreadDigests) { filter = "digests" }
            Chip("Archived", filter == "archived") { filter = "archived" }
            Chip("Elsewhere", false) { Ui.push(Route.Elsewhere) }
        }
        val ptr = rememberPullToRefreshState()
        PullToRefreshBox(refreshing, { scope.launch { refreshing = true; runCatching { Hub.sync(full = true) }.onFailure { Ui.say(it.message) }; refreshing = false } },
            Modifier.fillMaxSize(), state = ptr,
            indicator = { PullToRefreshDefaults.Indicator(state = ptr, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter), containerColor = p.s1, color = p.acc) }) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
                if (list.isEmpty()) item {
                    if (q.isNotBlank()) Empty("Nothing found", "No chat mentions \"$q\".")
                    else Empty("All quiet", "Start new work with the pen, or wait for ${agentName()} and cron to report in.")
                }
                items(list, key = { it.id }) { c -> ChatRow(c, c.id in work, c.id in need) }
            }
        }
    }
}

@Composable
fun ChatRow(c: Chat, live: Boolean, needs: Boolean) {
    val hfS = androidx.compose.ui.platform.LocalHapticFeedback.current
    val p = P
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val digest = c.kind == "digest"
    Box {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { Ui.open(c.id) }, onLongClick = { hfS.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); menu = true }).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(when { needs -> p.attd; digest -> p.s2; else -> p.s3 }), contentAlignment = Alignment.Center) {
                when {
                    needs -> Icn(Ic.shield, p.att, 21.dp)
                    digest -> Icn(Ic.digest, p.tx2, 21.dp)
                    else -> Text(c.title.trim().take(1).uppercase().ifBlank { "H" }, style = serif(24f, p.tx))
                }
                if (live) Box(Modifier.align(Alignment.BottomEnd).size(14.dp).clip(CircleShape).background(p.bg), contentAlignment = Alignment.Center) { LiveDot(8.dp) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (c.pinned) { Icn(Ic.pin, p.mu, 13.dp); Spacer(Modifier.width(4.dp)) }
                    if (c.profile.isNotBlank() && c.profile != "default") { Pill(c.profile, p.tx2, p.s2); Spacer(Modifier.width(6.dp)) }
                    Text(c.title, style = sans(15.5f, if (c.unread > 0) 650 else 550, p.tx), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(rel(c.updated), style = mono(11.5f, 500, if (c.unread > 0) p.acct else p.mu))
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val sub = when {
                        needs -> "Waiting for your approval"
                        live -> "${agentName()} is working…"
                        else -> (if (c.lastRole == "user") "You: " else "") + c.preview
                    }
                    Text(sub, style = sans(14f, 400, when { needs -> p.att; live -> p.acct; else -> p.mu }), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (c.unread > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(c.unread.coerceAtMost(99).toString(), style = mono(11f, 700, p.ink), modifier = Modifier.clip(CircleShape).background(p.acc).padding(horizontal = 7.dp, vertical = 1.dp))
                    }
                }
            }
        }
        DropdownMenu(menu, { menu = false }, containerColor = p.s2) {
            DropdownMenuItem({ Text(if (c.pinned) "Unpin" else "Pin", style = sans(15f, 500, p.tx)) }, { menu = false; scope.launch { runCatching { Hub.patch(c.id, "pinned", if (c.pinned) 0 else 1) } } })
            DropdownMenuItem({ Text(if (c.archived) "Unarchive" else "Archive", style = sans(15f, 500, p.tx)) }, { menu = false; scope.launch { runCatching { Hub.patch(c.id, "archived", if (c.archived) 0 else 1) } } })
            if (c.unread > 0) DropdownMenuItem({ Text("Mark read", style = sans(15f, 500, p.tx)) }, { menu = false; scope.launch { Hub.markRead(c.id) } })
        }
    }
}

// ---------------------------------------------------------------- chat
private val HIDE = setOf("deleted")

@Composable
fun ChatScreen(cid: String) {
    val p = P
    val ctx = LocalContext.current
    val act = LocalAct.current
    val scope = rememberCoroutineScope()
    val chats by Hub.chats.collectAsState()
    val all by Hub.msgs.collectAsState()
    val work by Hub.working.collectAsState()
    val appr by Hub.approvals.collectAsState()
    val chat = chats[cid]
    val msgs = all[cid].orEmpty().filter { it.kind !in HIDE }
    val since = work[cid]
    val running = since != null
    var text by rememberSaveable(cid) { mutableStateOf("") }
    val atts = rememberSaveable(cid, saver = AttachmentsSaver) { mutableStateListOf<JSONObject>() }
    var busy by remember { mutableStateOf(false) }
    var mode by remember(cid) { mutableStateOf("steer") }
    var rec by remember { mutableStateOf<Recorder?>(null) }
    androidx.compose.runtime.DisposableEffect(rec) { val active = rec; onDispose { active?.cancel() } }
    var camPath by rememberSaveable { mutableStateOf<String?>(null) }
    var plus by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    DisposableEffect(cid) { Hub.openChat = cid; onDispose { if (Hub.openChat == cid) Hub.openChat = null } }
    var loadErr by remember(cid) { mutableStateOf<String?>(null) }
    var loading by remember(cid) { mutableStateOf(true) }
    var retry by remember(cid) { mutableStateOf(0) }
    LaunchedEffect(cid, retry) {
        loading = true; loadErr = null
        try { Hub.loadChat(cid); Hub.markRead(cid) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { loadErr = e.message ?: "Connection failed" }
        finally { loading = false }
    }
    LaunchedEffect(cid) { runCatching { Hub.loadCmds() } }
    var first by remember(cid) { mutableStateOf(true) }
    var pending by remember(cid) { mutableStateOf(false) }
    var following by remember(cid) { mutableStateOf(false) }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if (dragging) { first = false; following = false } }
    val nearEnd by remember(list) { androidx.compose.runtime.derivedStateOf {
        val li = list.layoutInfo; val last = li.visibleItemsInfo.lastOrNull()?.index ?: 0
        li.totalItemsCount == 0 || last >= li.totalItemsCount - 1 - 3
    } }
    LaunchedEffect(nearEnd) { if (nearEnd) pending = false }
    LaunchedEffect(msgs.size, chat?.unread) {
        if (msgs.isNotEmpty()) {
            if (!dragging && (first || following || nearEnd)) {
                first = false
                following = true
                // A fast reply/unread update cancels and restarts this effect. Carry
                // the follow intent until scrolling finishes; a user drag revokes it.
                try { list.animateScrollToItem(msgs.size + 1) }
                catch (e: kotlinx.coroutines.CancellationException) {
                    // A competing user/accessibility scroll cancels the animation,
                    // not this effect's Job. A new message cancels the whole Job.
                    if (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true) following = false
                    throw e
                }
                following = false
            } else pending = true
            if ((chat?.unread ?: 0) > 0) Hub.markRead(cid)
        }
    }
    fun add(u: android.net.Uri, kind: String? = null) { scope.launch { busy = true; runCatching { atts += uploadUri(ctx, u, kind) }.onFailure { Ui.say("Upload failed: " + it.message) }; busy = false } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> add(u, "image") } }
    val doc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> add(u) } }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = camPath?.let(::File); if (ok && f != null) scope.launch { busy = true; runCatching { atts += uploadFile(f, "image/jpeg", "image") }.onFailure { Ui.say(it.message) }; busy = false }
    }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { val f = newMediaFile(ctx, "cam", ".jpg"); camPath = f.path; runCatching { cam.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)) }.onFailure { f.delete(); Ui.say("No camera app is available. Attach a photo instead.") } }
        else Ui.say("Camera permission denied. You can still attach a photo.")
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) rec = runCatching { Recorder(ctx) }.onFailure { Ui.say("Could not start recording: ${it.message}") }.getOrNull() else Ui.say("Microphone permission denied. You can still type a message.") }
    fun send(extra: List<JSONObject> = emptyList()) {
        val t = text.trim()
        val a = atts.toList() + extra
        if ((t.isEmpty() && a.isEmpty()) || busy) return
        busy = true
        following = true // Sending is explicit intent even if user and reply coalesce into one frame.
        val m = if (running && a.isEmpty()) mode else "send"
        scope.launch {
            try { Hub.send(cid, t, m, a); text = ""; atts.clear() }
            catch (e: kotlinx.coroutines.CancellationException) { following = false; throw e }
            catch (e: Exception) { following = false; Ui.say("Not sent: " + e.message) }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        val use = Hub.usage.collectAsState().value[cid]
    val live = use?.optJSONObject("turn")?.takeIf { running && it.optDouble("updated", 0.0) >= (since ?: 0.0) - 1 }
    LaunchedEffect(cid, msgs.size, running) { runCatching { Hub.loadUsage(cid) }; if (!running) runCatching { Ctl.loadChat(cid) } }
    val sub = when {
        // While working: model and cost only. The full token line is one tap away in the run screen.
        running -> "working" + (live?.let { l -> listOfNotNull(shortModel(l.str("model")).takeIf { it.isNotBlank() }, costStr(l)).joinToString(" · ").takeIf { it.isNotBlank() }?.let { " · $it" } } ?: "")
        chat?.kind == "digest" -> "digest"
        else -> headerLine(use?.optJSONObject("session"), use?.optJSONObject("turn"))
    }
    TopBar(chat?.title ?: "Chat", sub, if (running) p.acct else p.mu) {
            if (running) {
                Row(Modifier.clip(RoundedCornerShape(14.dp)).background(p.accd).clickable { Ui.push(Route.RunR(cid)) }.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(7.dp); Spacer(Modifier.width(7.dp)); Elapsed(since!!, mono(12.5f, 600, p.acct))
                }
                Spacer(Modifier.width(6.dp))
            } else IconBtn(Ic.tool, p.tx2) { Ui.push(Route.RunR(cid)) }
            if (chat?.kind != "digest") ChatMenu(cid, running) { t -> text = t }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)) {
            if (loadErr != null) item { ErrorPanel("Could not load chat: $loadErr") { retry++ } }
            else if (msgs.isEmpty()) item {
                if (loading) Skeleton() else Empty("New chat", "Say something to ${agentName()}.")
            }
            items(msgs.size, key = { msgs[it].id }) { i ->
                val m = msgs[i]
                val prev = msgs.getOrNull(i - 1)
                val next = msgs.getOrNull(i + 1)
                val day = remember(m.ts) { dayLabel(m.ts) }
                val prevDay = remember(prev?.ts) { prev?.ts?.let { dayLabel(it) } }
                if (i == 0 || day != prevDay) {
                    Text(day, style = mono(11f, 600, p.dim), modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                if (Hub.isNotice(m)) {
                    // Consecutive notices fold into one row; the first of the run owns it.
                    if (prev != null && Hub.isNotice(prev) && prevDay == day) return@items
                    var j = i; val run = ArrayList<Msg>()
                    while (j < msgs.size && Hub.isNotice(msgs[j])) { run += msgs[j]; j++ }
                    NoticeRow(run)
                    return@items
                }
                // Footer (time, cost) only on the last message of a same-role run: one stamp per exchange, not per bubble.
                // Activity cards and notices between two replies do not break the run.
                var k = i + 1
                while (k < msgs.size && msgs[k].role == m.role && (msgs[k].kind in setOf("activity", "trace") || Hub.isNotice(msgs[k]))) k++
                val nxt = msgs.getOrNull(k)
                val last = nxt == null || nxt.role != m.role
                MsgView(m, appr, i > 0 && msgs[i - 1].role == m.role && m.kind == "trace" && msgs[i - 1].kind == "trace", last)
            }
            if (running) item { WorkingRow(since!!) { Ui.push(Route.RunR(cid)) } }
            item { Spacer(Modifier.height(8.dp)) }
        }
        if (!nearEnd && msgs.isNotEmpty()) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).height(48.dp).clip(RoundedCornerShape(20.dp)).background(p.s2)
                .border(1.dp, p.line2, RoundedCornerShape(20.dp)).clickable { pending = false; scope.launch { list.animateScrollToItem(msgs.size + 1) } }
                .padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                Text(if (pending) "New messages ↓" else "Jump to latest", style = sans(13.5f, 600, if (pending) p.acct else p.tx))
            }
        }
        }
        // ---- composer
        Column(Modifier.fillMaxWidth().background(p.bg).navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (running) Row(Modifier.padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Seg(listOf("steer" to "Steer", "queue" to "Queue"), mode) { mode = it }
                Spacer(Modifier.width(10.dp))
                Text(if (mode == "steer") "redirects before the next step" else "runs after this one finishes", style = sans(12.5f, 400, p.mu), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            else Row(Modifier.padding(start = 2.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ChatModelControl(cid)
            }
            if (chat?.kind == "digest" && !running) DigestActions(cid, msgs.lastOrNull { it.role != "user" && it.kind == "text" }) { t -> text = ""; scope.launch { runCatching { Hub.send(cid, t) }.onFailure { Ui.say("Not sent: " + it.message) } } }
            SlashSuggest(text, { text = it }, Modifier.padding(bottom = 8.dp))
            if (atts.isNotEmpty()) AttRow(atts)
            val r = rec
            if (r != null) {
                RecordBar(r, onCancel = { r.cancel(); rec = null }) {
                    val f = r.stop(); rec = null
                    if (f != null) scope.launch { busy = true; val up = runCatching { uploadFile(f, "audio/ogg", "voice") }; busy = false; up.onSuccess { send(listOf(it)) }.onFailure { Ui.say(it.message) } }
                }
            } else Row(verticalAlignment = Alignment.Bottom) {
                Box {
                    IconBtn(Ic.plus, p.tx2, 46.dp, p.s2) { plus = true }
                    DropdownMenu(plus, { plus = false }, containerColor = p.s2) {
                        DropdownMenuItem({ Text("Photo", style = sans(15f, 500, p.tx)) }, { plus = false; pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, leadingIcon = { Icn(Ic.plus, p.tx2, 18.dp) })
                        DropdownMenuItem({ Text("Camera", style = sans(15f, 500, p.tx)) }, { plus = false; camPerm.launch(Manifest.permission.CAMERA) }, leadingIcon = { Icn(Ic.camera, p.tx2, 18.dp) })
                        DropdownMenuItem({ Text("File", style = sans(15f, 500, p.tx)) }, { plus = false; doc.launch(arrayOf("*/*")) }, leadingIcon = { Icn(Ic.file, p.tx2, 18.dp) })
                    }
                }
                Spacer(Modifier.width(8.dp))
                InputBox(text, { text = it }, if (running) (if (mode == "steer") "Steer ${agentName()}…" else "Queue a follow-up…") else "Message ${agentName()}", Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                if (running && text.isBlank() && atts.isEmpty()) StopCircle { scope.launch { runCatching { Hub.stop(cid); Ui.say("Stopping…") }.onFailure { Ui.say(it.message) } } }
                else if (text.isBlank() && atts.isEmpty()) IconBtn(Ic.mic, p.tx, 46.dp, p.s2) {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) rec = runCatching { Recorder(ctx) }.getOrNull()
                    else mic.launch(Manifest.permission.RECORD_AUDIO)
                } else IconBtn(Ic.send, p.ink, 46.dp, p.acc, enabled = !busy) { send() }
            }
        }
    }
}

@Composable
fun WorkingRow(since: Double, onClick: () -> Unit) {
    val p = P
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(p.accd).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        LiveDot(8.dp)
        Spacer(Modifier.width(10.dp))
        Text("${agentName()} is working", style = sans(14f, 600, p.acct))
        Spacer(Modifier.width(10.dp))
        Elapsed(since, mono(12.5f, 500, p.acct))
        Spacer(Modifier.width(6.dp))
        Icn(Ic.chevR, p.acct, 16.dp)
    }
}

@Composable
fun MsgView(m: Msg, appr: Map<String, Appr>, compact: Boolean, footer: Boolean = true) {
    if (m.meta.optBoolean("undone")) { Box(Modifier.alpha(0.38f)) { MsgBody(m, appr, compact, footer) }; return }
    MsgBody(m, appr, compact, footer)
}

/** One or more gateway notices (progress, restart, recall) as a single thin line. Tap to see them all;
 *  a recall notice also unfolds the injected facts themselves (meta.recall). */
@Composable
fun NoticeRow(run: List<Msg>) {
    val p = P
    var open by remember(run.first().id) { mutableStateOf(false) }
    val lastLine = Hub.plain(run.last().content).lineSequence().firstOrNull().orEmpty()
    val expandable = run.size > 1 || run.any { it.meta.str("recall").isNotEmpty() }
    Column(Modifier.fillMaxWidth().clickable(enabled = expandable) { open = !open }.padding(horizontal = 20.dp, vertical = 3.dp).animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).clip(androidx.compose.foundation.shape.CircleShape).background(p.dim))
            Spacer(Modifier.width(8.dp))
            Text(if (run.size == 1) lastLine else if (open) "${run.size} notices" else "${run.size} notices · $lastLine", style = mono(11.5f, 500, p.dim), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (expandable) Icn(if (open) Ic.chevU else Ic.chevD, p.dim, 13.dp)
            Text(clock(run.last().ts), style = mono(10.5f, 500, p.dim), modifier = Modifier.padding(start = 6.dp))
        }
        if (open) for (m in run) {
            if (run.size > 1) Text(Hub.plain(m.content), style = mono(11.5f, 400, p.mu), modifier = Modifier.padding(start = 13.dp, top = 4.dp))
            val recall = m.meta.str("recall")
            if (recall.isNotEmpty()) Text(recall, style = sans(12.5f, 400, p.mu, 17f), modifier = Modifier.padding(start = 13.dp, top = 4.dp))
        }
    }
}

/** Reasoning the gateway prepended to a reply, folded into a tappable "Thinking" row. */
@Composable
fun ThinkingRow(text: String) {
    val p = P
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(horizontal = 6.dp, vertical = 4.dp).animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Thinking", style = mono(11.5f, 600, p.mu))
            Spacer(Modifier.width(6.dp))
            Icn(if (open) Ic.chevU else Ic.chevD, p.dim, 14.dp)
        }
        if (open) Text(text, style = sans(13.5f, 400, p.mu, 19f), modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun MsgBody(m: Msg, appr: Map<String, Appr>, compact: Boolean, footer: Boolean = true) {
    val p = P
    val mine = m.role == "user"
    when (m.kind) {
        "trace" -> TraceRow(m, compact)
        "activity" -> ActivityCard(m)
        "status" -> Text(Hub.plain(m.content), style = mono(12.5f, 500, p.mu), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
        "approval" -> ApprovalCard(appr[m.meta.str("approval_id")], m)
        else -> {
            val atts = m.meta.optJSONArray("attachments").objs() +
                (if (m.kind in setOf("image", "file", "voice") && m.meta.has("media")) listOf(JSONObject(m.meta.toString()).put("kind", m.kind)) else emptyList())
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                if (m.kind == "steer" || m.kind == "queued") Text(if (m.kind == "steer") "STEER" else "QUEUED", style = mono(10.5f, 700, p.acct), modifier = Modifier.padding(end = 6.dp, bottom = 3.dp))
                val body = @Composable {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (a in atts) AttachmentView(a, mine)
                        if (m.kind == "image" && m.meta.has("url")) TapToLoadImage(m.meta.str("url"))
                        if (m.content.isNotBlank()) {
                            if (mine) Text(m.content, style = sans(15.5f, 400, p.tx, 23f))
                            else {
                                val think = remember(m.content) { Talker.reasoningOf(m.content) }
                                if (think != null) ThinkingRow(think)
                                Md(remember(m.content) { Talker.stripReasoning(m.content) })
                            }
                        }
                    }
                }
                if (mine) {
                    Box(Modifier.widthIn(max = 310.dp).clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)).background(if (m.kind == "steer") p.accd else p.s3)
                        .reportable(m).padding(horizontal = 15.dp, vertical = 10.dp)) { body() }
                } else {
                    Box(Modifier.fillMaxWidth().reportable(m).padding(horizontal = 6.dp, vertical = 4.dp)) { body() }
                }
                val u = if (mine) null else m.meta.optJSONObject("usage")
                if (u != null) UsageFooter(u, m.ts)
                else if (footer) Text(clock(m.ts), style = mono(10.5f, 500, p.dim), modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
fun TraceRow(m: Msg, compact: Boolean) {
    val p = P
    var open by remember { mutableStateOf(false) }
    val steps = m.meta.optJSONArray("steps").objs()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 1.dp else 4.dp).clip(RoundedCornerShape(16.dp)).background(p.s1)
        .border(1.dp, p.line, RoundedCornerShape(16.dp)).clickable { open = !open }.animateContentSize().padding(horizontal = 12.dp, vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icn(Ic.tool, p.mu, 15.dp)
            Spacer(Modifier.width(8.dp))
            val names = steps.map { it.str("tool") }.distinct()
            Text(if (steps.isEmpty()) "Tool activity" else "${steps.size} step${if (steps.size == 1) "" else "s"} · " + names.take(3).joinToString(", ") + if (names.size > 3) "…" else "",
                style = mono(12.5f, 500, p.tx2), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icn(if (open) Ic.chevU else Ic.chevD, p.mu, 16.dp)
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            if (steps.isEmpty()) Text(m.content, style = mono(12f, 400, p.tx2))
            for (s in steps) Row(Modifier.padding(vertical = 3.dp)) {
                Text(s.str("tool"), style = mono(12f, 600, p.acct), modifier = Modifier.width(96.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.str("what"), style = mono(12f, 400, p.tx2), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** What the agent was doing when it asked: files it touched this turn, recent steps, the last diff (collapsed). */
@Composable
fun ApprovalContext(meta: JSONObject, pending: Boolean) {
    val p = P
    val files = meta.optJSONArray("files").strs()
    val recent = meta.optJSONArray("recent").objs()
    val diff = meta.str("diff")
    if (files.isEmpty() && recent.isEmpty() && diff.isBlank()) return
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.s1).clickable { open = !open }.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CONTEXT", style = mono(10.5f, 700, p.mu))
            Spacer(Modifier.width(8.dp))
            Text(listOfNotNull(files.size.takeIf { it > 0 }?.let { "$it file" + if (it > 1) "s" else "" },
                recent.size.takeIf { it > 0 }?.let { "$it recent steps" }, if (diff.isNotBlank()) "last diff" else null).joinToString(" · "),
                style = mono(11.5f, 500, p.tx2), modifier = Modifier.weight(1f))
            Icn(if (open) Ic.chevU else Ic.chevD, p.mu, 16.dp)
        }
        if (open || (pending && files.isNotEmpty() && files.size <= 3)) for (f in files) Text(f, style = mono(11.5f, 500, p.tx), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp))
        if (open) {
            for (r in recent.take(6)) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(if (r.str("status") == "error") p.bad else if (r.str("status") == "running") p.acct else p.good, 6.dp)
                Spacer(Modifier.width(8.dp))
                Text(r.str("label").ifBlank { r.str("tool") }, style = sans(12.5f, 400, p.tx2), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                val ad = r.optInt("added"); val rm = r.optInt("removed")
                if (ad + rm > 0) Text("+$ad −$rm", style = mono(11f, 600, p.mu))
            }
            if (diff.isNotBlank()) { Spacer(Modifier.height(8.dp)); DiffPane(diff) }
        }
    }
}

@Composable
fun ApprovalCard(a: Appr?, m: Msg) {
    val p = P
    val act = LocalAct.current
    var busy by remember { mutableStateOf(false) }
    val state = a?.state ?: "pending"
    val pending = state == "pending"
    val cmd = a?.command ?: m.meta.str("command")
    val desc = a?.description ?: m.meta.str("description")
    val acts = a?.actions?.ifEmpty { null } ?: listOf(Act("Approve", "once", "primary"), Act("Deny", "deny", "danger"))
    fun go(choice: String) {
        val id = a?.id ?: m.meta.str("approval_id")
        if (busy || id.isBlank()) return
        busy = true
        Approvals.run(act, id, choice, cmd) { err -> busy = false; if (!err.isNullOrEmpty()) Ui.say(err) }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).clip(RoundedCornerShape(22.dp)).background(if (pending) p.attd else p.s1)
        .border(1.dp, if (pending) p.att.copy(alpha = 0.45f) else p.line, RoundedCornerShape(22.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icn(Ic.shield, if (pending) p.att else p.mu, 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(when (state) { "pending" -> "Needs your approval"; "approved" -> "Approved" + (a?.choice?.takeIf { it != "once" }?.let { " · $it" } ?: ""); "denied" -> "Denied"; else -> "Expired" },
                style = sans(14.5f, 650, if (pending) p.att else p.mu))
            Spacer(Modifier.weight(1f))
            Text(clock(m.ts), style = mono(11f, 500, p.mu))
        }
        if (desc.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(desc, style = sans(14.5f, 400, p.tx, 21f)) }
        if (cmd.isNotBlank()) { Spacer(Modifier.height(10.dp)); CodeBlock(cmd, "command") }
        if (m.meta.optBoolean("smart_denied")) Text("Hermes' safety check flagged this one. Read it before approving.",
            style = sans(13f, 600, p.bad), modifier = Modifier.padding(top = 8.dp))
        ApprovalContext(m.meta, pending)
        // Without a screen lock the approval key can't require a fingerprint: say so on the card itself, and
        // offer only this-once (session/always would widen what runs unchecked from a key nothing protects).
        if (pending && Prefs.noLock) Text("No screen lock on this phone, so approving doesn't ask for your fingerprint. Set a screen lock and re-pair to fix.",
            style = sans(13f, 600, p.bad), modifier = Modifier.padding(top = 8.dp))
        if (pending) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val main = acts.filter { it.choice != "deny" }
                val first = main.firstOrNull()
                if (first != null) Btn(if (busy) "…" else first.label, Modifier.weight(1f), enabled = !busy) { go(first.choice) }
                Btn("Deny", Modifier.weight(1f), danger = true, enabled = !busy) { go("deny") }
            }
            val rest = acts.filter { it.choice != "deny" }.drop(1).filter { !Prefs.noLock || it.choice == "once" }
            if (rest.isNotEmpty()) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (x in rest) Btn(x.label, Modifier.weight(1f), primary = false, enabled = !busy) { go(x.choice) }
            }
        }
    }
}

// ---------------------------------------------------------------- run
@Composable
fun RunScreen(cid: String) {
    val p = P
    val work by Hub.working.collectAsState()
    val all by Hub.msgs.collectAsState()
    val chat = Hub.chats.collectAsState().value[cid]
    val scope = rememberCoroutineScope()
    var info by remember(cid) { mutableStateOf<JSONObject?>(null) }
    var loadErr by remember(cid) { mutableStateOf<String?>(null) }
    var retry by remember(cid) { mutableStateOf(0) }
    var steer by rememberSaveable(cid) { mutableStateOf("") }
    val since = work[cid]
    VisibleEffect(cid, all[cid]?.size, since, retry) {
        loadErr = null
        do {
            try { info = Api.get("/v1/chats/$cid/run"); loadErr = null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { loadErr = e.message ?: "Connection failed" }
            if (since != null) delay(4000)
        } while (since != null)
    }
    val steps = info?.optJSONArray("steps").objs()
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar("Run", chat?.title)
        LazyColumn(Modifier.weight(1f)) {
            if (loadErr != null) item { ErrorPanel("Could not load run: $loadErr") { retry++ } }
            else if (info == null) item { Skeleton() }
            if (info != null) {
            item {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (since != null) { LiveDot(10.dp); Spacer(Modifier.width(10.dp)) }
                        Text(if (since != null) "Working" else "Idle", style = serif(44f, if (since != null) p.acct else p.tx))
                        Spacer(Modifier.weight(1f))
                        if (since != null) Elapsed(since, mono(22f, 500, p.tx))
                    }
                    Spacer(Modifier.height(14.dp))
                    val liveTurn = Hub.usage.collectAsState().value[cid]?.optJSONObject("turn")
                    RunUsage(liveTurn ?: info?.optJSONObject("usage"), info?.optJSONObject("session"), Hub.meta.collectAsState().value.str("name"))
                }
            }
            item { Section("Steps", if (steps.isEmpty()) null else "${steps.size}") }
            if (steps.isEmpty()) item { Text(if (since != null) "Waiting for the first tool call…" else "No tool calls in the latest turn.", style = sans(14f, 400, p.mu), modifier = Modifier.padding(horizontal = 20.dp)) }
            items(steps.size) { i ->
                val s = steps[i]
                val last = i == steps.lastIndex && since != null
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(22.dp)) {
                        if (last) LiveDot(10.dp) else Dot(p.s4, 10.dp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(s.str("tool"), style = mono(13f, 600, if (last) p.acct else p.tx))
                            Spacer(Modifier.weight(1f))
                            Text(clock(s.optDouble("ts", 0.0)), style = mono(11f, 500, p.dim))
                        }
                        if (s.str("what").isNotBlank()) Text(s.str("what"), style = mono(12.5f, 400, p.tx2), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
            }
        }
        if (since != null) Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                InputBox(steer, { steer = it }, "Steer before the next step…", Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                IconBtn(Ic.send, p.ink, 46.dp, p.acc, enabled = steer.isNotBlank()) {
                    val t = steer.trim(); scope.launch { runCatching { Hub.send(cid, t, "steer"); steer = ""; Ui.say("Steer sent") }.onFailure { Ui.say(it.message) } }
                }
            }
            Spacer(Modifier.height(8.dp))
            Btn("Stop run", Modifier.fillMaxWidth(), danger = true) { scope.launch { runCatching { Hub.stop(cid); Ui.say("Stopping…") }.onFailure { Ui.say(it.message) } } }
        }
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(P.s1).border(1.dp, P.line, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(value, style = mono(18f, 600, P.tx), maxLines = 1)
        Text(label, style = mono(11f, 500, P.mu))
    }
}
