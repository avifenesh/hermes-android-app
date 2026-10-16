@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.avifenesh.hermes

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.withResumed
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

data class Share(val text: String?, val uri: Uri?, val type: String?, val flags: Int,
    val generation: Long? = if (Prefs.paired) Api.epoch.capture() else null)

sealed interface Route {
    data class ChatR(val id: String) : Route
    data class RunR(val id: String) : Route
    data class ActR(val id: String, val chat: String) : Route
    data class JobR(val id: String) : Route
    data class SessR(val id: String, val title: String, val source: String) : Route
    data object Elsewhere : Route
    data object WakeR : Route
    data object SettingsR : Route
    data object PhoneR : Route
    data object AllSettingsR : Route
    data object ExtR : Route
    data object MemoryR : Route
    data class SkillR(val name: String) : Route
    data class CronEditR(val id: String?) : Route
    data object SearchR : Route
    data object UsageR : Route
    data class AgentR(val id: String, val name: String) : Route
    data object TalkR : Route
    data object ProjectsR : Route
    data class RepoR(val id: String) : Route
    data object HubR : Route
    data class HubSkillR(val ident: String) : Route
    data class HubPluginR(val name: String) : Route
    data class HubMcpR(val name: String) : Route
    data object RadarR : Route
    data class LegalR(val document: String? = null) : Route
    data object PrivacyR : Route
}

fun Route.available(features: Features): Boolean = when (this) {
    is Route.AgentR -> features.fleet
    is Route.RadarR -> features.radar
    is Route.ProjectsR, is Route.RepoR -> features.projects
    else -> true
}

object Ui {
    val theme = mutableIntStateOf(0)
    val accent = mutableIntStateOf(0)
    val paired = mutableStateOf(false)
    val pairLink = mutableStateOf<Uri?>(null)
    val share = mutableStateOf<Share?>(null)
    val composeOpen = mutableStateOf(false)
    /** Set by the wake word: Talk starts listening as soon as it shows. */
    val talkListen = mutableStateOf(false)
    val tab = mutableStateOf("inbox")
    val stack = mutableStateListOf<Route>()
    val toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Process-local selection, scoped to the originating machine epoch and route. */
    internal val supportRequest = mutableStateOf<SupportRequest?>(null)
    fun say(s: String?) { if (!s.isNullOrBlank()) toast.tryEmit(s) }
    fun push(r: Route) {
        supportRequest.value = null
        if (!r.available(Hub.features.value)) {
            stack.clear(); tab.value = if (r is Route.AgentR) "pulse" else "me"
        } else if (stack.lastOrNull() != r) stack.add(r)
    }
    fun open(cid: String) { supportRequest.value = null; composeOpen.value = false; stack.removeAll { it == Route.ChatR(cid) }; stack.add(Route.ChatR(cid)) }
    fun pop() { supportRequest.value = null; if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
}

val LocalAct = staticCompositionLocalOf<FragmentActivity> { error("no activity") }

@Composable
fun Root(act: FragmentActivity) {
    CompositionLocalProvider(LocalAct provides act) {
        Box(Modifier.fillMaxSize().background(P.bg).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
            val paired by Ui.paired
            val link by Ui.pairLink
            val prePairDocument = if (!paired && link == null) Ui.stack.lastOrNull() else null
            if (prePairDocument is Route.LegalR || prePairDocument == Route.PrivacyR) {
                BackHandler { Ui.pop() }
                if (prePairDocument is Route.LegalR) LicensesScreen(prePairDocument.document) else PrivacyScreen()
            } else if (!paired || link != null) PairScreen(link) else Main()
            Toasts()
        }
    }
}

@Composable
fun Toasts() {
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { Ui.toast.collect { msg = it; delay(2600); if (msg == it) msg = null } }
    Box(Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(bottom = 96.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(msg != null, enter = fadeIn(), exit = fadeOut()) {
            Text(msg ?: "", style = sans(14f, 500, P.bg), modifier = Modifier.padding(horizontal = 24.dp).clip(RoundedCornerShape(14.dp))
                .background(P.tx).padding(horizontal = 16.dp, vertical = 11.dp))
        }
    }
    SupportDialogs()
}

@Composable
fun Main() {
    val ctx = LocalContext.current
    val activity = LocalAct.current
    LaunchedEffect(activity) {
        activity.lifecycle.withResumed { (activity as? MainActivity)?.requestNotificationsOnce() }
    }
    LaunchedEffect(Unit) {
        App.schedule(ctx)
        LiveService.sync(ctx)
        Hub.connect()
        runCatching { Hub.sync() }.onFailure { Ui.say("Sync failed: " + it.message) }
        runCatching { Hub.refreshMe() }
        runCatching { Wake.refresh(ctx) }
        runCatching { if (Push.refresh(ctx) && Prefs.live) { Prefs.live = false; LiveService.sync(ctx) } }
    }
    val stack = Ui.stack
    val routeState = rememberSaveableStateHolder()
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val features by Hub.features.collectAsState()
    LaunchedEffect(features) {
        val hidden = stack.lastOrNull()?.takeUnless { it.available(features) }
        stack.removeAll { !it.available(features) }
        if (hidden != null && stack.isEmpty()) Ui.tab.value = if (hidden is Route.AgentR) "pulse" else "me"
    }
    BackHandler(enabled = stack.isNotEmpty()) { Ui.pop() }
    BackHandler(enabled = stack.isEmpty() && Ui.tab.value != "inbox") { Ui.tab.value = "inbox" }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (stack.isNotEmpty() || Ui.composeOpen.value)
            Modifier.clearAndSetSemantics { } else Modifier)) {
            // Keep scrollable content above the 62dp navigation + 14dp top/bottom spacing.
            Box(Modifier.fillMaxSize().padding(bottom = 90.dp)) {
                when (Ui.tab.value) {
                    "pulse" -> PulseScreen()
                    "cron" -> CronScreen()
                    "me" -> MeScreen()
                    else -> InboxScreen()
                }
            }
            NavBar()
        }
        val top = stack.lastOrNull()
        val depth = stack.size
        var lastDepth by remember { mutableIntStateOf(depth) }
        val pushing = depth >= lastDepth
        SideEffect { lastDepth = depth }
        AnimatedContent(top, transitionSpec = {
            val push = pushing
            when {
                initialState == null -> (slideInHorizontally { direction * it / 3 } + fadeIn()) togetherWith fadeOut()
                targetState == null -> fadeIn() togetherWith (slideOutHorizontally { direction * it / 3 } + fadeOut())
                push -> (slideInHorizontally { direction * it } + fadeIn()) togetherWith (slideOutHorizontally { -direction * it / 4 } + fadeOut())
                else -> (slideInHorizontally { -direction * it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { direction * it } + fadeOut())
            }
        }, label = "stack") { shown ->
            if (shown != null && shown.available(features)) Box(Modifier.fillMaxSize().background(P.bg)) {
                val routeKey = "${Api.epoch.capture()}:$shown"
                routeState.SaveableStateProvider(routeKey) {
                androidx.compose.runtime.DisposableEffect(routeKey) { onDispose { if (shown !in Ui.stack) routeState.removeState(routeKey) } }
                when (val r = shown) {
                    is Route.ChatR -> ChatScreen(r.id)
                    is Route.RunR -> RunScreen(r.id)
                    is Route.ActR -> ActivityScreen(r.id, r.chat)
                    is Route.JobR -> JobScreen(r.id)
                    is Route.SessR -> SessionScreen(r)
                    is Route.Elsewhere -> ElsewhereScreen()
                    is Route.WakeR -> WakeSettingsScreen()
                    is Route.SettingsR -> SettingsScreen()
                    is Route.PhoneR -> PhoneSettingsScreen()
                    is Route.AllSettingsR -> AllSettingsScreen()
                    is Route.ExtR -> ExtScreen()
                    is Route.MemoryR -> MemoryScreen()
                    is Route.SkillR -> SkillScreen(r.name)
                    is Route.CronEditR -> CronEditScreen(r.id)
                    is Route.SearchR -> SearchScreen()
                    is Route.UsageR -> UsageScreen()
                    is Route.AgentR -> AgentScreen(r.id, r.name)
                    is Route.TalkR -> TalkScreen()
                    is Route.ProjectsR -> ProjectsScreen()
                    is Route.RepoR -> RepoScreen(r.id)
                    is Route.HubR -> HubScreen()
                    is Route.HubSkillR -> HubSkillScreen(r.ident)
                    is Route.HubPluginR -> HubPluginScreen(r.name)
                    is Route.HubMcpR -> HubMcpScreen(r.name)
                    is Route.RadarR -> RadarScreen()
                    is Route.LegalR -> LicensesScreen(r.document)
                    is Route.PrivacyR -> PrivacyScreen()
                    null -> {}
                }
                }
            }
        }
        if (Ui.composeOpen.value) ComposeSheet()
    }
}

@Composable
fun BoxScope.NavBar() {
    val p = P
    val appr by Hub.approvals.collectAsState()
    val chats by Hub.chats.collectAsState()
    val work by Hub.working.collectAsState()
    val need = appr.values.count { it.state == "pending" }
    val unread = chats.values.count { it.unread > 0 && !it.archived }
    fun b(n: Int) = if (n > 99) "99+" else n.toString()
    Row(Modifier.align(Alignment.BottomCenter).widthIn(max = 600.dp).fillMaxWidth().navigationBarsPadding().padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).height(62.dp).clip(CircleShape).background(p.nav).border(1.dp, p.line2, CircleShape).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            NavItem("inbox", "Inbox", Ic.inbox, if (unread > 0) b(unread) else null, false, Modifier.weight(1f))
            NavItem("pulse", "Pulse", Ic.pulse, if (need > 0) b(need) else if (work.isNotEmpty()) b(work.size) else null, need > 0, Modifier.weight(1f))
            NavItem("cron", "Cron", Ic.cron, null, false, Modifier.weight(1f))
            NavItem("me", "Me", Ic.me, null, false, Modifier.weight(1f))
        }
        Box(Modifier.size(62.dp).shadow(10.dp, CircleShape, ambientColor = p.acc, spotColor = p.acc)
            .clip(CircleShape).background(p.acc).semantics { contentDescription = "New chat" }
            .clickable(role = androidx.compose.ui.semantics.Role.Button) { Ui.composeOpen.value = true }, contentAlignment = Alignment.Center) { Icn(Ic.pen, p.ink, 24.dp) }
    }
}

@Composable
fun NavItem(key: String, label: String, icon: ImageVector, badge: String?, hot: Boolean, modifier: Modifier = Modifier) {
    val p = P
    val sel = Ui.tab.value == key
    val bg by androidx.compose.animation.animateColorAsState(if (sel) p.s3 else Color.Transparent, label = "nav")
    Box(modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(bg).selectable(selected = sel, role = androidx.compose.ui.semantics.Role.Tab) { Ui.tab.value = key; Ui.stack.clear() }
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icn(icon, if (sel) p.tx else p.mu, 23.dp)
        if (badge != null) Text(badge, style = mono(10f, 700, if (hot) p.ink else p.bg), textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)
            .widthIn(min = 18.dp).clip(CircleShape).background(if (hot) p.att else p.tx).padding(horizontal = 4.dp))
    }
}

// ---------------------------------------------------------------- pairing
@Composable
fun PairScreen(link: Uri?) {
    val p = P
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cb = LocalClipboardManager.current
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var paste by rememberSaveable { mutableStateOf("") }
    val scannerAvailable = remember(ctx) { Push.available(ctx) }
    fun go(u: Uri, confirmed: Boolean = false) {
        if (busy) return
        // Never drop a live pairing on a link alone: only the explicit "Pair with this machine" tap passes confirmed.
        if (Ui.paired.value && !confirmed) { err = "Tap Pair with this machine to switch"; return }
        if (u.scheme != "hermes" || u.host != "pair") { err = "That isn't a Hermes pairing link"; return }
        busy = true; err = null
        scope.launch {
            try {
                PhoneService.disarm(ctx)
                Machines.park()
                // pair commits credentials atomically only after a valid response. Nothing to roll back on failure.
                Api.pair(u)
                Prefs.seq = 0; Prefs.nseq = 0; Prefs.live = true
                Machines.park()
                Ui.stack.clear(); Ui.tab.value = "inbox"
                Ui.pairLink.value = null; Ui.paired.value = true
                // Main owns sync / metadata / push bootstrap after this screen leaves composition.
                Ui.say("Paired with " + (Prefs.server ?: "Hermes"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                err = when (e) { is ApiError -> if (e.code == 403) "That code is used or expired. Run hermes mobile pair again." else e.message; else -> e.message ?: e.toString() }
            } finally { busy = false }
        }
    }
    // A link that arrives from outside (deep link / QR) while already paired never pairs by itself: switching the
    // phone to another server is a deliberate tap, so a malicious link can't silently redirect it.
    var pendingLink by remember(link) { mutableStateOf(if (Ui.paired.value && link?.getQueryParameter("c") != null) link else null) }
    LaunchedEffect(link) { if (link != null && link.getQueryParameter("c") != null && !Ui.paired.value) go(link) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp)) {
        Spacer(Modifier.height(54.dp))
        MarkBox(58.dp)
        Spacer(Modifier.height(26.dp))
        Text("Hermes", style = serif(64f, p.tx, italic = true))
        Text("${agentName()}, in your pocket. Chats, approvals, runs and cron, straight from your machine.", style = sans(16f, 400, p.tx2, 24f))
        Spacer(Modifier.height(34.dp))
        Text("1  On your machine", style = mono(12f, 600, p.mu))
        Spacer(Modifier.height(8.dp))
        CodeBlock("hermes mobile pair", "shell")
        Spacer(Modifier.height(22.dp))
        Text(if (scannerAvailable) "2  Scan the code, or paste the link" else "2  Paste the pairing link", style = mono(12f, 600, p.mu))
        Spacer(Modifier.height(10.dp))
        if (scannerAvailable) {
            Btn(if (busy) "Pairing…" else "Scan QR code", Modifier.fillMaxWidth(), enabled = !busy) {
                GmsBarcodeScanning.getClient(ctx).startScan()
                    .addOnSuccessListener { b -> val v = b.rawValue; if (v != null) go(Uri.parse(v.trim()), confirmed = true) else err = "Empty QR code" }
                    .addOnFailureListener { err = "QR scanner unavailable. Paste the pairing link instead." }
            }
        } else {
            Text(if (busy) "Pairing…" else "QR scanning needs Google Play services. You can still pair with a link.", style = sans(14f, 400, p.tx2))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            InputBox(paste, { paste = it }, "hermes://pair?…", Modifier.weight(1f), maxLines = 3, size = 14f)
            Spacer(Modifier.width(8.dp))
            Btn(if (paste.isBlank()) "Paste" else "Pair", primary = false, enabled = !busy) {
                val t = paste.ifBlank { cb.getText()?.text.orEmpty() }.trim()
                paste = t
                if (t.isNotBlank()) go(Uri.parse(t), confirmed = true)
            }
        }
        val e = err
        if (e != null) {
            Spacer(Modifier.height(16.dp))
            Text(e, style = sans(14f, 500, p.bad))
        }
        val pl = pendingLink
        if (pl != null) {
            Spacer(Modifier.height(16.dp))
            Text("Pair with " + (Uri.parse(pl.getQueryParameter("u") ?: "").host ?: "?") + "? Your current machines stay paired.", style = sans(14.5f, 500, p.tx))
            Spacer(Modifier.height(8.dp))
            Btn("Pair with this machine", Modifier.fillMaxWidth(), enabled = !busy) { pendingLink = null; go(pl, confirmed = true) }
        }
        if (link != null && Ui.paired.value) {
            Spacer(Modifier.height(10.dp))
            Btn("Cancel", primary = false) { Ui.pairLink.value = null }
        }
        Spacer(Modifier.height(40.dp))
        Text("The link carries a one-time code. Your phone creates its own signing keys. Approvals require biometric or screen-lock authentication.", style = sans(13f, 400, p.dim, 19f))
        if (!Ui.paired.value && link == null) {
            Spacer(Modifier.height(16.dp))
            Btn("Privacy policy", Modifier.fillMaxWidth(), primary = false, enabled = !busy) { Ui.push(Route.PrivacyR) }
            Spacer(Modifier.height(8.dp))
            Btn("Licenses", primary = false, enabled = !busy) { Ui.push(Route.LegalR()) }
        }
        Spacer(Modifier.height(30.dp))
    }
}

// ---------------------------------------------------------------- compose (new work)
@Composable
fun ComposeSheet() {
    val p = P
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by rememberSaveable { mutableStateOf("") }
    val atts = rememberSaveable(saver = AttachmentsSaver) { mutableStateListOf<JSONObject>() }
    var busy by remember { mutableStateOf(false) }
    var rec by remember { mutableStateOf<Recorder?>(null) }
    androidx.compose.runtime.DisposableEffect(rec) { val active = rec; onDispose { active?.cancel() } }
    var camPath by rememberSaveable { mutableStateOf<String?>(null) }
    var mpick by remember { mutableStateOf<JSONObject?>(null) }
    var prof by remember { mutableStateOf(Prefs.lastProfile ?: "default") }
    var profs by remember { mutableStateOf(listOf("default")) }
    val meta by Hub.meta.collectAsState()
    val chatsNow by Hub.chats.collectAsState()
    val lastReply = remember(chatsNow) { chatsNow.values.filter { !it.archived && it.kind != "digest" && it.lastRole == "agent" && it.preview.isNotBlank() }.maxByOrNull { it.updated } }
    fun add(u: Uri, kind: String? = null, generation: Long = Api.epoch.capture(), externalFlags: Int? = null) {
        busy = true
        scope.launch {
            try {
                externalFlags?.let { ExternalShare.validate(ctx, u, it) }
                atts += uploadUri(ctx, u, kind, generation)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Ui.say("Upload failed: ${e.message}") }
            finally { busy = false }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> add(u, "image") } }
    val doc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> add(u) } }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = camPath?.let(::File); if (ok && f != null) scope.launch { busy = true; runCatching { atts += uploadFile(f, "image/jpeg", "image") }.onFailure { Ui.say(it.message) }; busy = false }
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) rec = runCatching { Recorder(ctx) }.onFailure { Ui.say("Could not start recording: ${it.message}") }.getOrNull() else Ui.say("Microphone permission denied. You can still type a message.") }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { val f = newMediaFile(ctx, "cam", ".jpg"); camPath = f.path; runCatching { cam.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)) }.onFailure { f.delete(); Ui.say("No camera app is available. Attach a photo instead.") } }
        else Ui.say("Camera permission denied. You can still attach a photo.")
    }
    val shared = Ui.share.value
    LaunchedEffect(shared) {
        shared?.let { s ->
            s.text?.let { text = it }
            if (s.uri == null) Ui.share.value = null
        }
    }
    LaunchedEffect(Unit) {
        runCatching { Hub.loadCmds(force = true) }
        runCatching { profs = Api.get("/v1/profiles").optJSONArray("profiles").strs().ifEmpty { listOf("default") } }
    }
    fun send() {
        val t = text.trim()
        if ((t.isEmpty() && atts.isEmpty()) || busy) return
        busy = true
        scope.launch {
            try {
                val title = t.lineSequence().firstOrNull()?.take(48)?.ifBlank { null } ?: if (atts.any { it.str("kind") == "voice" }) "Voice note" else "New chat"
                val cid = Hub.newChat(title, prof)
                Prefs.lastProfile = prof
                if (prof == "default") mpick?.let { pk ->
                    // Picker semantics (as Hermes' own pickers): the user chose this model explicitly, so a cost guard is confirmed.
                    val o = Ctl.apply(cid, JSONObject(pk.toString()))
                    val c = o.optJSONObject("confirm")
                    if (c != null) Ctl.confirm(cid, c.str("id"), "once")
                    else if (!o.optBoolean("ok", true)) Ui.say("Model not switched: " + Hub.plain(o.str("error")).take(160))
                }
                Hub.send(cid, t, "send", atts.toList())
                mpick = null
                text = ""; atts.clear()
                Ui.open(cid)
            } catch (e: Exception) { Ui.say("Couldn't start: " + e.message) } finally { busy = false }
        }
    }
    ModalBottomSheet(onDismissRequest = { rec?.cancel(); Ui.share.value = null; Ui.composeOpen.value = false }, sheetState = sheet, containerColor = p.s1,
        dragHandle = { Box(Modifier.padding(top = 10.dp).size(40.dp, 4.dp).clip(CircleShape).background(p.s4)) }) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("New work", style = serif(36f, p.tx), modifier = Modifier.weight(1f))
                IconBtn(Ic.mic, p.acct, 40.dp, p.accd) { Ui.composeOpen.value = false; Ui.push(Route.TalkR) }
            }
            if (lastReply != null && text.isBlank()) {
                Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.s2).clickable { Ui.open(lastReply.id) }
                    .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Continue last reply", style = sans(13.5f, 600, p.tx))
                        Text(lastReply.title + " · " + lastReply.preview, style = sans(12.5f, 400, p.mu), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icn(Ic.chevR, p.mu, 16.dp)
                }
            }
            if (profs.size > 1) {
                Spacer(Modifier.height(12.dp))
                Text("New chat in", style = mono(11f, 600, p.mu))
                Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (x in profs) Chip(x, x == prof) { prof = x }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (prof == "default") FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ComposeModelControl(mpick) { mpick = it }
                Spacer(Modifier.width(8.dp))
                Chip("Mixture of agents", text.startsWith("/moa ")) { text = if (text.startsWith("/moa ")) text.removePrefix("/moa ") else "/moa " + text }
            } else Text("Runs in the $prof profile with its own model, memory and keys.", style = sans(12.5f, 400, p.mu))
            Spacer(Modifier.height(10.dp))
            InputBox(text, { text = it }, "Ask ${agentName()} anything, or type / for commands", Modifier.fillMaxWidth().heightIn(min = 120.dp), minLines = 4, maxLines = 10)
            SlashSuggest(text, { text = it }, Modifier.padding(top = 6.dp))
            if (atts.isNotEmpty()) AttRow(atts)
            Spacer(Modifier.height(14.dp))
            val r = rec
            if (r != null) {
                RecordBar(r, onCancel = { r.cancel(); rec = null }) {
                    val f = r.stop(); rec = null
                    if (f != null) scope.launch { busy = true; runCatching { atts += uploadFile(f, "audio/ogg", "voice") }.onFailure { Ui.say(it.message) }; busy = false; send() }
                }
            } else Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SrcBtn(Ic.mic, "Voice") { if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) rec = runCatching { Recorder(ctx) }.getOrNull() else mic.launch(Manifest.permission.RECORD_AUDIO) }
                SrcBtn(Ic.camera, "Camera") { camPerm.launch(Manifest.permission.CAMERA) }
                SrcBtn(Ic.plus, "Photo") { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                SrcBtn(Ic.file, "File") { doc.launch(arrayOf("*/*")) }
                }
                IconBtn(Ic.send, p.ink, 50.dp, p.acc, enabled = !busy && (text.isNotBlank() || atts.isNotEmpty())) { send() }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
    if (shared?.uri != null) HDialog("Upload shared file?", onDismiss = { Ui.share.value = null }, body = {
        Text("This uploads the shared file to ${Prefs.base ?: "your paired machine"}. Nothing is uploaded until you confirm.", style = sans(15f, 400, p.tx2))
    }, actions = {
        Btn("Cancel", primary = false) { Ui.share.value = null }
        Btn("Upload", enabled = !busy) {
            Ui.share.value = null
            val generation = shared.generation ?: Api.epoch.capture()
            if (generation != Api.epoch.capture()) Ui.say("Machine changed. Share the file again.")
            else add(shared.uri, generation = generation, externalFlags = shared.flags)
        }
    })
}

@Composable
fun SrcBtn(v: ImageVector, label: String, on: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(14.dp)).semantics { contentDescription = label }.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = on).padding(horizontal = 6.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(P.s3), contentAlignment = Alignment.Center) { Icn(v, P.tx, 20.dp) }
        Spacer(Modifier.height(4.dp))
        Text(label, style = sans(11f, 500, P.mu))
    }
}

@Composable
fun AttRow(atts: MutableList<JSONObject>) {
    Row(Modifier.padding(top = 10.dp).fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (a in atts.toList()) Row(Modifier.clip(RoundedCornerShape(12.dp)).background(P.s3).padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icn(when (a.str("kind")) { "image" -> Ic.camera; "voice" -> Ic.mic; else -> Ic.file }, P.tx2, 15.dp)
            Spacer(Modifier.width(6.dp))
            Text(a.str("file_name").take(22), style = sans(12.5f, 500, P.tx), maxLines = 1)
            IconBtn(Ic.x, P.mu, 28.dp) { atts.remove(a) }
        }
    }
}

@Composable
fun RecordBar(r: Recorder, onCancel: () -> Unit, onSend: () -> Unit) {
    val p = P
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.s2).border(1.dp, p.attd, RoundedCornerShape(22.dp)).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Ic.x, p.mu, 42.dp) { onCancel() }
        LiveDot(9.dp, p.att)
        Spacer(Modifier.width(10.dp))
        Text("Recording", style = sans(15f, 500, p.tx))
        Spacer(Modifier.width(10.dp))
        Elapsed(r.started / 1000.0, mono(14f, 500, p.mu))
        Spacer(Modifier.weight(1f))
        IconBtn(Ic.send, p.ink, 46.dp, p.acc) { onSend() }
    }
}
