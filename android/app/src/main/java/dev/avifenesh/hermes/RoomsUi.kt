@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.avifenesh.hermes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/** Callbacks of the room screen, so the debug probe can drive the same composables with fixtures. */
class RoomCallbacks(
    val send: () -> Unit = {},
    val attachPhoto: (() -> Unit)? = null,
    val attachFile: (() -> Unit)? = null,
    val older: () -> Unit = {},
    val retry: () -> Unit = {},
    val act: (RoomAction, String) -> Unit = { _, _ -> },
    val mention: (RoomMember) -> Unit = {},
    val members: () -> Unit = {},
    val stop: () -> Unit = {},
    val rename: () -> Unit = {},
    val delete: () -> Unit = {},
    val jump: () -> Unit = {},
)

@Composable
fun memberColor(index: Int): Color {
    val (_, night, day) = Accents[index % Accents.size]
    return if (P.dark) night else day
}

@Composable
fun MemberAvatar(name: String, index: Int, size: Dp = 30.dp) {
    val c = memberColor(index)
    Box(Modifier.size(size).clip(CircleShape).background(c.copy(alpha = if (P.dark) 0.2f else 0.16f)), contentAlignment = Alignment.Center) {
        Text(RoomLogic.initial(name), style = mono(size.value * 0.42f, 700, c))
    }
}

/** A group's mark: its first two members' initials, overlapped, in the chat-row tile. */
@Composable
fun RoomTile(r: Room, needs: Boolean = false) {
    val p = P
    Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(if (needs) p.attd else p.s3), contentAlignment = Alignment.Center) {
        if (needs) { Icn(Ic.shield, p.att, 21.dp); return@Box }
        val shown = r.members.take(2)
        if (shown.isEmpty()) { Icn(Ic.users, p.tx2, 21.dp); return@Box }
        Box(Modifier.size(34.dp)) {
            shown.forEachIndexed { i, m ->
                Box(Modifier.align(if (i == 0) Alignment.TopStart else Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(p.s3).padding(1.5.dp)) {
                    MemberAvatar(m.name, RoomLogic.colorIndex(r.members, m.id, Accents.size), 19.dp)
                }
            }
        }
    }
}

@Composable
fun RoomRow(r: Room, onOpen: () -> Unit, onMarkRead: () -> Unit = {}) {
    val p = P
    var menu by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val answering = RoomLogic.answering(r.members, r.working)
    Box {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); menu = true
        }).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                RoomTile(r)
                if (answering.isNotEmpty()) Box(Modifier.align(Alignment.BottomEnd).size(14.dp).clip(CircleShape).background(p.bg), contentAlignment = Alignment.Center) { LiveDot(8.dp) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, style = sans(15.5f, if (r.unread > 0) 650 else 550, p.tx), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    Pill("${r.members.size}", p.tx2, p.s2)
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(rel(r.updated), style = mono(11.5f, 500, if (r.unread > 0) p.acct else p.mu))
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val sub = if (answering.isNotEmpty()) RoomLogic.workingLine(answering) + "…" else RoomLogic.previewLine(r)
                    Text(sub, style = sans(14f, 400, if (answering.isNotEmpty()) p.acct else p.mu), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (r.unread > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(r.unread.coerceAtMost(99).toString(), style = mono(11f, 700, p.ink), modifier = Modifier.clip(CircleShape).background(p.acc).padding(horizontal = 7.dp, vertical = 1.dp))
                    }
                }
            }
        }
        DropdownMenu(menu, { menu = false }, containerColor = p.s2) {
            DropdownMenuItem({ Text("Open", style = sans(15f, 500, p.tx)) }, { menu = false; onOpen() })
            if (r.unread > 0) DropdownMenuItem({ Text("Mark read", style = sans(15f, 500, p.tx)) }, { menu = false; onMarkRead() })
        }
    }
}

/** The first row of the Rooms filter: start a group with two to six Bots. */
@Composable
fun NewRoomRow(onClick: () -> Unit) {
    val p = P
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(p.accd), contentAlignment = Alignment.Center) { Icn(Ic.plus, p.acct, 21.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("New group chat", style = sans(15.5f, 600, p.tx))
            Text("Two to six Bots in one room", style = sans(13.5f, 400, p.mu), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- room screen
@Composable
fun RoomScreen(rid: String) {
    val ctx = LocalContext.current
    val act = LocalAct.current
    val scope = rememberCoroutineScope()
    val rooms by Rooms.list.collectAsState()
    val room = rooms[rid]
    val all by Rooms.events.collectAsState()
    val events = all[rid].orEmpty()
    val driver = Rooms.drivers.collectAsState().value[rid]
    val hasOlder = Rooms.before.collectAsState().value[rid] != null
    val driverUp by Rooms.driverUp.collectAsState()
    var draft by rememberSaveable(rid) { mutableStateOf("") }
    // One client id per intended message: a retry after a lost answer is the same send, never a second one.
    var clientId by rememberSaveable(rid) { mutableStateOf<String?>(null) }
    val atts = rememberSaveable(rid, saver = AttachmentsSaver) { mutableStateListOf<JSONObject>() }
    var busy by remember { mutableStateOf(false) }
    var loading by remember(rid) { mutableStateOf(true) }
    var err by remember(rid) { mutableStateOf<String?>(null) }
    var retry by remember(rid) { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<RoomAction?>(null) }
    var actionBusy by remember { mutableStateOf<String?>(null) }
    val list = remember(rid) { LazyListState(Rooms.events.value[rid].orEmpty().size + 5, 0) }
    DisposableEffect(rid) { Rooms.openRoom = rid; onDispose { if (Rooms.openRoom == rid) Rooms.openRoom = null } }
    val latest = room?.latestSeq ?: 0L
    // Lifecycle-bound: the plugin's poll moves latest_seq over SSE, and the screen fetches what changed while shown.
    VisibleEffect(rid, latest, retry) {
        try { Rooms.refresh(rid); err = null }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (events.isEmpty()) err = e.message ?: "Connection failed" }
        finally { loading = false }
    }
    val working = driver?.working == true || room?.working?.isNotEmpty() == true
    // Pending approvals are not in the SSE summary: poll the room's driver state only while a member works.
    VisibleEffect(rid, working) {
        while (working) {
            delay(3000)
            try { Rooms.refresh(rid) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    val nearEnd by remember(list) { derivedStateOf {
        val li = list.layoutInfo; val last = li.visibleItemsInfo.lastOrNull()?.index ?: 0
        li.totalItemsCount == 0 || last >= li.totalItemsCount - 1 - 2
    } }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    var landed by remember(rid) { mutableStateOf(false) }
    val pendingCount = driver?.pending?.size ?: 0
    LaunchedEffect(events.size, loading, pendingCount) {
        if (events.isEmpty() || loading) return@LaunchedEffect
        // Rows never outnumber events, plus a few fixed rows; an index past the end lands on the last row.
        val end = events.size + pendingCount + 8
        if (!landed) { landed = true; list.scrollToItem(end); return@LaunchedEffect }
        if (nearEnd && !dragging) list.animateScrollToItem(end)
    }
    VisibleEffect(rid, nearEnd, latest, room?.unread) {
        val r = room ?: return@VisibleEffect
        if (nearEnd && landed && (r.unread > 0 || r.readSeq < r.latestSeq)) Rooms.markRead(rid, r.latestSeq)
    }
    fun add(u: android.net.Uri, kind: String? = null) {
        scope.launch { busy = true; runCatching { atts += uploadUri(ctx, u, kind) }.onFailure { Ui.say("Upload failed: " + it.message) }; busy = false; clientId = null }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> add(u, "image") } }
    val doc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> add(u) } }
    fun send() {
        val t = draft.trim()
        val a = atts.toList()
        if ((t.isEmpty() && a.isEmpty()) || busy) return
        val id = clientId ?: java.util.UUID.randomUUID().toString().also { clientId = it }
        busy = true
        scope.launch {
            try { Rooms.send(rid, t, a, id); draft = ""; atts.clear(); clientId = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Ui.say("Not sent: " + e.message) }
            finally { busy = false }
        }
    }
    fun run(key: String, block: suspend () -> Unit) {
        if (actionBusy != null) return
        actionBusy = key
        scope.launch {
            try { block(); Rooms.refresh(rid) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Ui.say(e.message) }
            finally { actionBusy = null }
        }
    }
    val cb = RoomCallbacks(
        send = ::send,
        attachPhoto = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        attachFile = { doc.launch(arrayOf("*/*")) },
        older = { scope.launch { runCatching { Rooms.older(rid) }.onFailure { Ui.say(it.message) } } },
        retry = { loading = true; retry++ },
        act = { a, choice ->
            when {
                a.kind == "approval" -> {
                    if (actionBusy == null) {
                        actionBusy = a.taskId
                        Approvals.runRoom(act, rid, a, choice) { e -> actionBusy = null; if (!e.isNullOrEmpty()) Ui.say(e); scope.launch { runCatching { Rooms.refresh(rid) } } }
                    }
                }
                choice == "confirmed" -> run(a.taskId) { Rooms.act(rid, a) }
                else -> confirm = a
            }
        },
        mention = { m -> draft = RoomLogic.mention(draft, m.handle); clientId = null },
        members = { sheet = true },
        stop = { run("stop") { Rooms.stop(rid); Ui.say("Stopping the group…") } },
        rename = { renaming = true },
        delete = { deleting = true },
    )
    RoomBody(room, events, driver, driverUp, loading, err, hasOlder, draft, { draft = it; clientId = null }, atts, busy,
        actionBusy, list, cb)
    if (sheet && room != null) RoomMembersSheet(room, driver, onDismiss = { sheet = false }) { m -> sheet = false; cb.mention(m) }
    if (renaming && room != null) RenameRoomDialog(room.name, onDismiss = { renaming = false }) { name ->
        renaming = false; run("rename") { Rooms.rename(rid, name) }
    }
    if (deleting) HDialog("Delete this group?", { deleting = false }, {
        Text("The group and its history go away for every device and for Hermes Desktop. Each Bot keeps its own chats.",
            style = sans(14.5f, 400, P.tx2, 21f))
    }) {
        Btn("Cancel", primary = false) { deleting = false }
        Btn("Delete", danger = true) {
            deleting = false
            scope.launch {
                try { Rooms.delete(rid); Ui.pop(); Ui.say("Group deleted") }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { Ui.say(e.message) }
            }
        }
    }
    confirm?.let { a ->
        val who = room?.members?.firstOrNull { it.id == a.member }?.name ?: a.member
        val discard = a.kind == "discard"
        HDialog(if (discard) "Discard $who's turn?" else "Run $who's turn again?", { confirm = null }, {
            Text(if (discard) "Hermes can't tell whether this turn finished. Discarding lets the group move on; it does not undo anything $who already did."
                else "$who's turn could not start. Running it again sends the same message to $who.", style = sans(14.5f, 400, P.tx2, 21f))
        }) {
            Btn("Cancel", primary = false) { confirm = null }
            Btn(if (discard) "Discard" else "Run again", danger = discard) { confirm = null; cb.act(a, "confirmed") }
        }
    }
}

@Composable
fun RoomBody(room: Room?, events: List<RoomEvent>, driver: RoomDriver?, driverUp: Boolean, loading: Boolean, error: String?,
             hasOlder: Boolean, draft: String, onDraft: (String) -> Unit, atts: MutableList<JSONObject>, busy: Boolean,
             actionBusy: String?, list: LazyListState, cb: RoomCallbacks) {
    val p = P
    val members = room?.members.orEmpty()
    val live = (room?.working.orEmpty() + driver?.members.orEmpty().filterValues { it != "queued" }.keys).toSet()
    val answering = RoomLogic.answering(members, live)
    val queued = RoomLogic.answering(members, driver?.members.orEmpty().filterValues { it == "queued" }.keys - live)
    val rows = remember(events) { RoomLogic.rows(events, ::dayLabel) }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding()) {
        val sub = when {
            answering.isNotEmpty() -> RoomLogic.workingLine(answering)
            else -> members.joinToString(", ") { it.name }.ifBlank { "Group chat" }
        }
        TopBar(room?.name ?: "Group chat", sub, if (answering.isNotEmpty()) p.acct else p.mu) {
            IconBtn(Ic.users, p.tx2, label = "Members") { cb.members() }
            Box {
                IconBtn(Ic.more, p.tx2) { menu = true }
                DropdownMenu(menu, { menu = false }, containerColor = p.s2) {
                    DropdownMenuItem({ Text("Members", style = sans(15f, 500, p.tx)) }, { menu = false; cb.members() })
                    DropdownMenuItem({ Text("Rename", style = sans(15f, 500, p.tx)) }, { menu = false; cb.rename() })
                    if (answering.isNotEmpty() || queued.isNotEmpty()) DropdownMenuItem({ Text("Stop the group", style = sans(15f, 500, p.tx)) }, { menu = false; cb.stop() })
                    DropdownMenuItem({ Text("Delete group", style = sans(15f, 500, p.bad)) }, { menu = false; cb.delete() })
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)) {
                if (error != null) item { ErrorPanel("Could not load the group: $error", cb.retry) }
                else if (events.isEmpty()) item {
                    if (loading) Skeleton() else Empty(room?.name ?: "Group chat", "Say something to the group. @mention a Bot to ask just that one.")
                }
                if (hasOlder && events.isNotEmpty()) item(key = "older") {
                    Text("Earlier messages", style = sans(13.5f, 600, p.acct), textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = cb.older).heightIn(min = 48.dp).padding(vertical = 14.dp))
                }
                items(rows, key = { it.event.seq }) { row ->
                    if (row.day) Text(dayLabel(row.event.ts), style = mono(11f, 600, p.dim), textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                    when {
                        row.notices.isNotEmpty() -> RoomNoticeRow(row.notices)
                        row.event.role == "user" -> OwnRoomMessage(room?.id.orEmpty(), row)
                        else -> MemberRoomMessage(room?.id.orEmpty(), row, members, cb.mention)
                    }
                }
                if (answering.isNotEmpty() || queued.isNotEmpty()) item(key = "working") { RoomWorkingRow(answering, queued) }
                // What the room waits on scrolls with the log, so a long command never squeezes the composer.
                items(driver?.pending.orEmpty(), key = { "act:${it.kind}:${it.taskId}:${it.generation}" }) { a ->
                    Box(Modifier.padding(horizontal = 14.dp)) { RoomActionCard(a, members, actionBusy == a.taskId, cb.act) }
                }
                item(key = "end") { Spacer(Modifier.height(8.dp)) }
            }
        }
        // ---- composer
        Column(Modifier.fillMaxWidth().background(p.bg).navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (!driverUp || (driver != null && !driver.running && room != null)) Text("The gateway's group chat worker is not running. Messages wait until it is back.",
                style = sans(13f, 500, p.attt), modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp))
            if (atts.isNotEmpty()) AttRow(atts)
            Row(verticalAlignment = Alignment.Bottom) {
                if (cb.attachPhoto != null || cb.attachFile != null) {
                    var plus by remember { mutableStateOf(false) }
                    Box {
                        IconBtn(Ic.plus, p.tx2, 46.dp, p.s2) { plus = true }
                        DropdownMenu(plus, { plus = false }, containerColor = p.s2) {
                            AttachmentPrivacyNotice(Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 10.dp))
                            cb.attachPhoto?.let { f -> DropdownMenuItem({ Text("Photo", style = sans(15f, 500, p.tx)) }, { plus = false; f() }, leadingIcon = { Icn(Ic.camera, p.tx2, 18.dp) }) }
                            cb.attachFile?.let { f -> DropdownMenuItem({ Text("File", style = sans(15f, 500, p.tx)) }, { plus = false; f() }, leadingIcon = { Icn(Ic.file, p.tx2, 18.dp) }) }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                InputBox(draft, onDraft, "Message the group", Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                if (draft.isBlank() && atts.isEmpty() && (answering.isNotEmpty() || queued.isNotEmpty())) StopCircle(cb.stop)
                else IconBtn(Ic.send, p.ink, 46.dp, p.acc, enabled = !busy && driverUp && (draft.isNotBlank() || atts.isNotEmpty()), onClick = cb.send)
            }
        }
    }
}

@Composable
fun RoomWorkingRow(answering: List<String>, queued: List<String>) {
    val p = P
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(p.accd).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        LiveDot(8.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            if (answering.isNotEmpty()) Text(RoomLogic.workingLine(answering), style = sans(14f, 600, p.acct))
            if (queued.isNotEmpty()) Text("Next: " + queued.joinToString(", "), style = sans(13f, 500, p.tx2))
        }
    }
}

@Composable
fun RoomNoticeRow(run: List<RoomEvent>) {
    val p = P
    var open by remember(run.first().seq) { mutableStateOf(false) }
    val last = RoomLogic.noticeText(run.last())
    Column(Modifier.fillMaxWidth().clickable(enabled = run.size > 1) { open = !open }.padding(horizontal = 20.dp, vertical = 3.dp).animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(if (run.any { it.notice == "turn.failed" }) p.bad else p.dim))
            Spacer(Modifier.width(8.dp))
            Text(if (run.size == 1) last else if (open) "${run.size} notices" else "${run.size} notices · $last", style = mono(11.5f, 500, p.dim),
                maxLines = if (open) 6 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (run.size > 1) Icn(if (open) Ic.chevU else Ic.chevD, p.dim, 13.dp)
            Text(clock(run.last().ts), style = mono(10.5f, 500, p.dim), modifier = Modifier.padding(start = 6.dp))
        }
        if (open) for (e in run) Text(RoomLogic.noticeText(e), style = mono(11.5f, 400, p.mu), modifier = Modifier.padding(start = 13.dp, top = 4.dp))
    }
}

@Composable
fun OwnRoomMessage(rid: String, row: RoomRowSpec) {
    val p = P
    val e = row.event
    Column(Modifier.fillMaxWidth().padding(start = 48.dp, end = 14.dp, top = if (row.head) 6.dp else 1.dp, bottom = 1.dp), horizontalAlignment = Alignment.End) {
        Box(Modifier.widthIn(max = 310.dp).clip(RoundedCornerShape(22.dp, 22.dp, if (row.tail) 6.dp else 22.dp, 22.dp)).background(p.s3)
            .padding(horizontal = 15.dp, vertical = 10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in e.attachments) RoomAttachment(rid, e, a, mine = true)
                if (e.text.isNotBlank()) Text(e.text, style = sans(15.5f, 400, p.tx, 23f))
            }
        }
        if (row.tail) Text(clock(e.ts), style = mono(10.5f, 500, p.dim), modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
fun MemberRoomMessage(rid: String, row: RoomRowSpec, members: List<RoomMember>, onMention: (RoomMember) -> Unit) {
    val p = P
    val e = row.event
    val member = members.firstOrNull { it.id == e.member }
    val name = e.name.ifBlank { member?.name ?: e.member.ifBlank { "Bot" } }
    val index = RoomLogic.colorIndex(members, e.member, Accents.size)
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 30.dp, top = if (row.head) 8.dp else 1.dp, bottom = 1.dp)) {
        Box(Modifier.width(30.dp)) {
            if (row.head) Box(Modifier.clip(CircleShape).clickable(enabled = member != null, role = Role.Button) { member?.let(onMention) }) {
                MemberAvatar(name, index)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            if (row.head) Row(Modifier.padding(start = 4.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = sans(13f, 650, memberColor(index)), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Text(clock(e.ts), style = mono(10.5f, 500, p.dim))
            }
            Box(Modifier.clip(RoundedCornerShape(if (row.head) 6.dp else 18.dp, 18.dp, 18.dp, 18.dp)).background(p.s1)
                .border(1.dp, p.line, RoundedCornerShape(if (row.head) 6.dp else 18.dp, 18.dp, 18.dp, 18.dp)).padding(horizontal = 13.dp, vertical = 9.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (a in e.attachments) RoomAttachment(rid, e, a, mine = false)
                    if (e.text.isNotBlank()) Md(e.text, size = 15f)
                }
            }
        }
    }
}

/** A room file: images load inline through the room's own file route; anything else opens or saves on tap. */
@Composable
fun RoomAttachment(rid: String, e: RoomEvent, a: JSONObject, mine: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val aid = a.str("attachment_id")
    val name = a.str("name").ifBlank { "attachment" }
    val mime = a.str("mime")
    val url = Rooms.fileUrl(rid, e.id, aid)
    if (rid.isBlank() || aid.isBlank() || e.id.isBlank()) return
    if (a.str("kind") == "image" || mime.startsWith("image/")) { RemoteImage(url); return }
    val size = a.optLong("size", -1).takeIf { it >= 0 }
    var prog by remember(url) { mutableStateOf<Float?>(null) }
    fun get(then: suspend (File) -> Unit) {
        if (prog != null) return
        prog = 0f
        scope.launch {
            runCatching {
                val f = File(mediaDir(ctx), "room_" + aid + "_" + name.replace('/', '_'))
                if (!f.exists() || (size != null && f.length() != size)) Api.download(url, f, size) { prog = it }
                then(f)
            }.onFailure { if (it !is CancellationException) Ui.say("Download failed: " + it.message) }
            prog = null
        }
    }
    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(if (mine) P.s4 else P.s2).clickable { get { openFile(ctx, it, mime) } }
        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icn(Ic.file, P.tx2, 20.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(name, style = sans(14f, 500, P.tx), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val pr = prog
            Text(if (pr != null) "Downloading… ${(pr * 100).toInt()}%" else listOfNotNull(size?.let { sizeStr(it) }, "tap to open").joinToString(" · "),
                style = mono(11f, 500, if (pr != null) P.acct else P.mu))
        }
        Spacer(Modifier.width(4.dp))
        IconBtn(Ic.download, P.tx2, 40.dp) { get { saveToDownloads(ctx, it, name, mime); Ui.say("Saved to Downloads: $name") } }
    }
}

@Composable
fun RoomActionCard(a: RoomAction, members: List<RoomMember>, busy: Boolean, onAct: (RoomAction, String) -> Unit) {
    val p = P
    val who = members.firstOrNull { it.id == a.member }?.name ?: a.member
    val approval = a.kind == "approval"
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(22.dp)).background(if (approval) p.attd else p.s1)
        .border(1.dp, if (approval) p.att.copy(alpha = 0.45f) else p.line, RoundedCornerShape(22.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icn(if (approval) Ic.shield else Ic.refresh, if (approval) p.att else p.mu, 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(when (a.kind) {
                "approval" -> "$who needs your approval"
                "discard" -> "$who's last turn has an unknown outcome"
                else -> "$who's turn is waiting to run"
            }, style = sans(14.5f, 650, if (approval) p.attt else p.tx), modifier = Modifier.weight(1f))
        }
        if (a.description.isNotBlank()) Text(a.description, style = sans(14f, 400, p.tx, 20f), modifier = Modifier.padding(top = 8.dp))
        if (a.command.isNotBlank()) { Spacer(Modifier.height(8.dp)); CodeBlock(a.command, "command") }
        if (approval && Prefs.noLock) Text("No screen lock on this phone, so approving doesn't ask for your fingerprint.",
            style = sans(13f, 600, p.bad), modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (a.kind) {
                "approval" -> {
                    Btn(if (busy) "…" else "Approve", Modifier.weight(1f), enabled = !busy) { onAct(a, "once") }
                    Btn("Deny", Modifier.weight(1f), danger = true, enabled = !busy) { onAct(a, "deny") }
                }
                "discard" -> Btn(if (busy) "…" else "Discard turn", Modifier.weight(1f), primary = false, enabled = !busy) { onAct(a, "") }
                else -> Btn(if (busy) "…" else "Run again", Modifier.weight(1f), primary = false, enabled = !busy) { onAct(a, "") }
            }
        }
    }
}

@Composable
fun RoomMembersSheet(room: Room, driver: RoomDriver?, onDismiss: () -> Unit, onMention: (RoomMember) -> Unit) {
    val p = P
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.s1,
        dragHandle = { Box(Modifier.padding(top = 10.dp).size(40.dp, 4.dp).clip(CircleShape).background(p.s4)) }) {
        RoomMembersContent(room, driver, onMention)
    }
}

@Composable
fun RoomMembersContent(room: Room, driver: RoomDriver?, onMention: (RoomMember) -> Unit) {
    val p = P
    Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Text("Members", style = serif(34f, p.tx), modifier = Modifier.padding(horizontal = 20.dp))
        Text("${room.members.size} Bots in ${room.name}. Tap one to @mention it.", style = sans(13.5f, 400, p.mu),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Spacer(Modifier.height(6.dp))
        for (m in room.members) {
            val status = driver?.members?.get(m.id) ?: if (m.id in room.working) "running" else ""
            val trail: (@Composable () -> Unit)? = if (status.isBlank()) null else {
                {
                    if (status == "queued") Pill("Next", p.tx2, p.s2) else Row(verticalAlignment = Alignment.CenterVertically) {
                        LiveDot(7.dp); Spacer(Modifier.width(6.dp)); Text("Answering", style = mono(11f, 600, p.acct))
                    }
                }
            }
            ListRow({ MemberAvatar(m.name, RoomLogic.colorIndex(room.members, m.id, Accents.size), 40.dp) }, m.name,
                listOfNotNull("@" + m.handle, m.profile.takeIf { it != m.handle }).joinToString(" · "), trail, { onMention(m) })
        }
        Text("Members are fixed for this group. To change who is in it, start a new group.", style = sans(12.5f, 400, p.mu),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
    }
}

@Composable
fun RenameRoomDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    HDialog("Rename group", onDismiss, {
        InputBox(name, { name = it.take(200) }, "Group name", Modifier.fillMaxWidth(), maxLines = 2)
    }) {
        Btn("Cancel", primary = false, onClick = onDismiss)
        Btn("Rename", enabled = name.isNotBlank() && name.trim() != current) { onRename(name.trim()) }
    }
}

/** New group chat: a name and two to six of the Bots this gateway serves. */
@Composable
fun NewRoomSheet(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val bots by Rooms.bots.collectAsState()
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { Rooms.load() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = P.s1,
        dragHandle = { Box(Modifier.padding(top = 10.dp).size(40.dp, 4.dp).clip(CircleShape).background(P.s4)) }) {
        NewRoomContent(bots, busy) { name, picked ->
            busy = true
            scope.launch {
                try {
                    val r = Rooms.create(name, picked)
                    onDismiss()
                    if (r != null) Ui.openRoom(r.id)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Ui.say("Couldn't start the group: " + e.message) }
                finally { busy = false }
            }
        }
    }
}

@Composable
fun NewRoomContent(bots: List<RoomBot>, busy: Boolean, initialName: String = "", initialPicked: Set<String> = emptySet(),
                   onCreate: (String, List<String>) -> Unit) {
    val p = P
    var name by rememberSaveable { mutableStateOf(initialName) }
    var picked by rememberSaveable { mutableStateOf(initialPicked.toList()) }
    Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text("New group chat", style = serif(34f, p.tx))
        Text("The group lives on this gateway, so Hermes Desktop sees it too.", style = sans(13.5f, 400, p.mu), modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        InputBox(name, { name = it.take(200) }, "Group name", Modifier.fillMaxWidth(), maxLines = 2)
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("BOTS", style = mono(11.5f, 600, p.mu), modifier = Modifier.weight(1f))
            Text("${picked.size} of 2–6", style = mono(11.5f, 500, if (picked.size in 2..6) p.acct else p.mu))
        }
        if (bots.size < 2) Text("This gateway serves fewer than two Bots. Create another profile (hermes profile create) to start a group.",
            style = sans(14f, 400, p.tx2, 20f), modifier = Modifier.padding(vertical = 8.dp))
        for ((i, b) in bots.withIndex()) {
            val on = b.profile in picked
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .toggleable(value = on, enabled = on || picked.size < 6, role = Role.Checkbox) { picked = if (on) picked - b.profile else picked + b.profile }
                .heightIn(min = 56.dp).padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                MemberAvatar(b.name, i + 1, 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.name, style = sans(15f, 600, p.tx), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (b.name != b.profile) Text("@" + b.profile, style = mono(12f, 500, p.mu), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(if (on) p.acc else Color.Transparent)
                    .border(1.5.dp, if (on) p.acc else p.line2, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    if (on) Icn(Ic.check, p.ink, 16.dp)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Btn(if (busy) "Starting…" else "Start group", Modifier.fillMaxWidth(), enabled = !busy && name.isNotBlank() && picked.size in 2..6) {
            onCreate(name.trim(), picked)
        }
        Spacer(Modifier.height(16.dp))
    }
}
