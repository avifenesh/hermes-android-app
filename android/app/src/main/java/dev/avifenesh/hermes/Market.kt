@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.avifenesh.hermes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Hub: browse, search and install skills, plugins and MCP servers from Hermes' own catalogs
 * (server: the /v1/hub routes in server/mobile/market.py). Every install runs on the machine through Hermes' installer and
 * security scan; the app shows the scan, asks for confirmation and the biometric key, then follows the job.
 */
object Mk {
    /** Target profile for skills; "" is the gateway's own profile. */
    val profile = MutableStateFlow("")
    val profiles = MutableStateFlow<List<String>>(emptyList())
    val gateway = MutableStateFlow("default")
    val tab = mutableStateOf("skills")
    val plugins = mutableMapOf<String, JSONObject>()
    val mcp = mutableMapOf<String, JSONObject>()

    suspend fun loadProfiles() {
        runCatching {
            val o = Api.get(HubLogic.withProfile("/v1/hub/skills/installed", profile.value))
            profiles.value = o.optJSONArray("profiles").strs()
            gateway.value = o.str("gateway_profile", "default")
        }
    }

    fun shownProfile(): String = profile.value.ifBlank { gateway.value }

    /** Plain call first; on 403 need_sig, the fingerprint signs "hub\n<key>\nsha(true)\nts" and the call is retried. */
    suspend fun guarded(act: FragmentActivity, key: String, title: String, send: suspend (Map<String, String>) -> JSONObject): JSONObject =
        Guard.call(act, "hub", key, true, title, send)

    /** Polls a job until it leaves "running", reporting each state. */
    suspend fun follow(start: JSONObject, tick: (JSONObject) -> Unit): JSONObject {
        var job = start
        tick(job)
        while (job.str("state") == "running") {
            delay(1000)
            job = runCatching { Api.get("/v1/hub/jobs/" + job.str("id")).getJSONObject("job") }.getOrElse { e ->
                JSONObject(job.toString()).put("state", "failed").put("error", "Lost track of the job: ${e.message}")
            }
            tick(job)
        }
        return job
    }
}

private fun JSONObject.counts(): Map<String, Int> = optJSONObject("severity_counts")?.let { c -> HubLogic.SEVERITIES.associateWith { c.optInt(it) } } ?: emptyMap()
private fun JSONArray?.objList(): List<JSONObject> = objs()
private fun JSONObject.skillText(): String = listOf(str("name"), str("description"), str("identifier"),
    optJSONArray("tags").strs().joinToString(" ")).joinToString(" ")
private fun JSONObject.skillCategory(): String = str("category").ifBlank {
    if (str("source") == "official") str("identifier").removePrefix("official/").substringBefore('/', "general") else "general"
}

@Composable
private fun trustColors(t: String): Pair<Color, Color> {
    val p = P
    return when (t) { "builtin" -> p.acct to p.accd; "trusted" -> p.good to p.s3; else -> p.att to p.attd }
}

@Composable
fun TrustPill(t: String) { val (fg, bg) = trustColors(t); Pill(HubLogic.trustLabel(t), fg, bg) }

// ---------------------------------------------------------------- the screen
@Composable
fun HubScreen() {
    val tab by Mk.tab
    val prof by Mk.profile.collectAsState()
    var pick by remember { mutableStateOf(false) }
    val profiles by Mk.profiles.collectAsState()
    LaunchedEffect(Unit) { Mk.loadProfiles() }
    Column(Modifier.fillMaxSize()) {
        TopBar("Hub", "Skills, plugins and MCP servers from Hermes' catalogs") {
            IconBtn(Ic.blocks, P.tx2) { if (Ui.stack.getOrNull(Ui.stack.lastIndex - 1) == Route.ExtR) Ui.pop() else Ui.push(Route.ExtR) }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Seg(listOf("skills" to "Skills", "plugins" to "Plugins", "mcp" to "MCP"), tab) { Mk.tab.value = it }
            Spacer(Modifier.weight(1f))
            if (tab == "skills" && profiles.size > 1) Text(Mk.shownProfile() + " ▾", style = mono(12.5f, 600, P.acct),
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(P.accd).clickable { pick = true }.padding(horizontal = 10.dp, vertical = 6.dp))
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                "plugins" -> HubPluginsTab()
                "mcp" -> HubMcpTab()
                else -> HubSkillsTab(prof)
            }
        }
    }
    if (pick) OptionSheet("Install skills into", profiles, Mk.shownProfile(), onPick = { v ->
        Mk.profile.value = if (v == Mk.gateway.value) "" else v; pick = false
    }, onDismiss = { pick = false })
}

@Composable
private fun Loading(err: String?, onRetry: (() -> Unit)? = null) {
    if (err != null) { if (onRetry != null) ErrorPanel(err, onRetry) else Text(err, style = sans(14f, 400, P.bad), modifier = Modifier.padding(20.dp)) } else Skeleton()
}

// ---------------------------------------------------------------- skills
@Composable
fun HubSkillsTab(prof: String) {
    val p = P
    val co = rememberCoroutineScope()
    var browse by remember(prof) { mutableStateOf<JSONObject?>(null) }
    var err by remember(prof) { mutableStateOf<String?>(null) }
    var q by remember(prof) { mutableStateOf("") }
    var scope by remember(prof) { mutableStateOf("discover") }
    var source by remember(prof) { mutableStateOf("all") }
    var category by remember(prof) { mutableStateOf("all") }
    var results by remember(prof) { mutableStateOf<JSONObject?>(null) }
    var searching by remember(prof) { mutableStateOf(false) }
    var searchErr by remember(prof) { mutableStateOf<String?>(null) }
    var updates by remember(prof) { mutableStateOf<List<JSONObject>?>(null) }
    var checking by remember(prof) { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    val foreground = Ui.stack.lastOrNull() == Route.HubR
    LaunchedEffect(prof, retry, foreground) {
        if (!foreground) return@LaunchedEffect
        err = null
        try { browse = Api.get(HubLogic.withProfile("/v1/hub/skills/browse", prof)) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { err = e.message }
    }
    LaunchedEffect(q, source, prof, scope, retry, foreground) {
        results = null; searchErr = null; searching = false
        val t = q.trim()
        if (!foreground || t.length < 2 || scope == "installed") return@LaunchedEffect
        searching = true
        try {
            delay(450)
            results = Api.get(HubLogic.withProfile("/v1/hub/skills/search?q=${HubLogic.enc(t)}&source=${HubLogic.enc(source)}&limit=50", prof))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { searchErr = e.message }
        finally { searching = false }
    }
    val b = browse
    val installed = b?.optJSONObject("installed") ?: JSONObject()
    val catalog = (b?.optJSONArray("official").objList() + b?.optJSONArray("featured").objList()).distinctBy { it.str("identifier") }
    val inventory = b?.optJSONArray("local").objList()
    val managed = installed.keys().asSequence().map { ident ->
        val e = installed.optJSONObject(ident) ?: JSONObject()
        val metadata = catalog.firstOrNull { it.str("identifier") == ident }
            ?: inventory.firstOrNull { it.str("identifier") == ident }
        JSONObject(e.toString()).put("identifier", ident).put("installed", true).put("managed", true)
            .put("category", metadata?.skillCategory() ?: "general").put("description", metadata?.str("description").orEmpty())
    }.toList()
    val local = inventory.filter { !it.optBoolean("managed") }.map {
        JSONObject(it.toString()).put("installed", true).put("source", "local")
    }
    val remote = q.trim().length >= 2 && scope != "installed"
    val available = if (remote) results?.optJSONArray("results").objList() else catalog
    val candidates = when {
        scope == "installed" -> managed + local
        scope == "all" -> available + (managed + local).filter { row ->
            (source == "all" || row.str("source") == source) &&
                (q.isBlank() || row.skillText().contains(q.trim(), true)) && available.none {
                (row.str("identifier").isNotBlank() && it.str("identifier") == row.str("identifier")) ||
                    it.str("installed_name") == row.str("name")
            }
        }
        else -> available
    }
    val filters = HubLogic.Filters(scope, if (remote) "all" else source, category, if (remote) "" else q)
    val featuredIds = b?.optJSONArray("featured").objList().map { it.str("identifier") }.toSet()
    val list = candidates.filter { e -> filters.matches(e.optBoolean("installed"),
        if (source == "hermes-index" && e.str("identifier") in featuredIds) "hermes-index" else e.str("source"), e.skillCategory(),
        e.skillText()) }
        .sortedBy { it.str("name").lowercase() }
    val categories = (catalog + candidates).map { it.skillCategory() }.distinct().sorted()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            HubScopes(scope) { scope = it; category = "all"; source = "all" }
            InputBox(q, { q = it }, if (scope == "installed") "Search installed skills" else "Search every skill registry", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), maxLines = 1)
            val srcs = b?.optJSONArray("sources").objList().filter { it.str("id") !in setOf("url", "well-known") && it.optBoolean("available", true) }
            if (scope != "installed" && srcs.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("All sources", source == "all") { source = "all"; category = "all" }
                for (s in srcs) Chip(s.str("label"), source == s.str("id")) { source = s.str("id"); category = "all" }
            }
            if (categories.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("All categories", category == "all") { category = "all" }
                for (c in categories) Chip(HubLogic.pretty(c), category == c) { category = c }
            }
            Text(if (scope == "discover") "Only skills not installed in ${Mk.shownProfile()}. Search to explore more registries." else "Skills in ${Mk.shownProfile()}. Source and category filters apply to this list.",
                style = sans(12.5f, 400, p.dim, 17f), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Section(if (remote) "Search results" else if (scope == "installed") "Installed skills" else "Browse skills", if (searching) "searching…" else list.size.toString())
            if (scope == "installed" && managed.isNotEmpty()) {
                Btn(if (checking) "Checking…" else "Check for updates", Modifier.padding(horizontal = 16.dp), primary = false, enabled = !checking) {
                    co.launch {
                        checking = true
                        runCatching { updates = Api.get(HubLogic.withProfile("/v1/hub/skills/updates", prof)).optJSONArray("updates").objList() }.onFailure { Ui.say(it.message) }
                        checking = false
                    }
                }
                updates?.let { u -> Text("${u.count { it.str("status") == "update_available" }} Hub updates available", style = mono(12.5f, 500, p.mu), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
            }
            val timedOut = results?.optJSONArray("timed_out").strs()
            if (timedOut.isNotEmpty()) Text("Timed out: " + timedOut.joinToString(", "), style = mono(11.5f, 500, p.att), modifier = Modifier.padding(horizontal = 20.dp))
        }
        when {
            b == null -> item { Loading(err) { retry++ } }
            remote && searchErr != null -> item { ErrorPanel(searchErr.orEmpty()) { retry++ } }
            remote && searching -> item { Skeleton() }
            list.isEmpty() -> item { Empty("No matching skills", if (scope == "discover") "Try another category, source or search. Installed skills are in Installed." else "Try another category or search word.") }
        }
        items(list, key = { "skill:" + it.str("identifier").ifBlank { it.str("inventory_id").ifBlank { it.str("name") } } }) { s ->
            if (s.optBoolean("installed") && !s.optBoolean("managed") && s.str("identifier").isBlank()) {
                NavRow(Ic.blocks, s.str("name"), s.str("description").ifBlank { "Installed locally · manage in Extensions" }) { Ui.push(Route.ExtR) }
            } else SkillRow(s)
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun HubScopes(scope: String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Discover", scope == "discover") { onPick("discover") }
        Chip("Installed", scope == "installed") { onPick("installed") }
        Chip("All", scope == "all") { onPick("all") }
    }
}

@Composable
fun SkillCard(s: JSONObject, wide: Boolean) {
    val p = P
    Column(Modifier.width(if (wide) 260.dp else 200.dp).heightIn(min = 128.dp).clip(RoundedCornerShape(20.dp)).background(p.s1)
        .border(1.dp, p.line, RoundedCornerShape(20.dp)).clickable { Ui.push(Route.HubSkillR(s.str("identifier"))) }.padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TrustPill(s.str("trust_level"))
            Spacer(Modifier.weight(1f))
            if (s.optBoolean("installed")) Icn(Ic.check, p.good, 16.dp)
        }
        Spacer(Modifier.height(8.dp))
        Text(s.str("name"), style = sans(16f, 600, p.tx), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(3.dp))
        Text(s.str("description"), style = sans(13f, 400, p.mu, 18f), maxLines = if (wide) 3 else 4, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SkillRow(s: JSONObject) {
    val p = P
    Row(Modifier.fillMaxWidth().clickable { Ui.push(Route.HubSkillR(s.str("identifier"))) }.padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.str("name"), style = sans(15f, 600, p.tx), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp)); TrustPill(s.str("trust_level"))
                if (s.optBoolean("installed")) { Spacer(Modifier.width(6.dp)); Pill("installed", p.good, p.s3) }
            }
            Text(s.str("identifier"), style = mono(11f, 500, p.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (s.str("description").isNotBlank()) Text(s.str("description"), style = sans(13f, 400, p.mu, 18f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icn(Ic.chevR, p.mu, 18.dp)
    }
}

// ---------------------------------------------------------------- job card + confirm
@Composable
fun JobPanel(job: JSONObject?, verb: String) {
    val p = P
    job ?: return
    val st = job.str("state")
    Panel(Modifier.padding(vertical = 8.dp), border = when (st) { "failed" -> p.bad.copy(alpha = 0.5f); "ok" -> p.good.copy(alpha = 0.5f); else -> p.line },
        bg = if (st == "failed") p.badd else p.s1) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (st) { "running" -> LiveDot(); "ok" -> Icn(Ic.check, p.good, 16.dp); else -> Icn(Ic.x, p.bad, 16.dp) }
                Spacer(Modifier.width(10.dp))
                Text(when (st) { "running" -> "$verb on ${job.str("profile")}…"; "ok" -> "Done"; "consent" -> "Needs your OK"; else -> "Failed" },
                    style = sans(15f, 600, p.tx))
                Spacer(Modifier.weight(1f))
                if (st == "running") Elapsed(job.optDouble("started", nowS()), mono(11.5f, 500, p.mu))
            }
            if (job.str("error").isNotBlank()) Text(job.str("error"), style = sans(13.5f, 500, p.bad, 19f), modifier = Modifier.padding(top = 6.dp))
            val res = job.optJSONObject("result")
            res?.optJSONArray("warnings").strs().forEach { Text("⚠ $it", style = sans(13f, 400, p.att, 18f), modifier = Modifier.padding(top = 4.dp)) }
            res?.optJSONArray("missing_env").strs().takeIf { it.isNotEmpty() }?.let {
                Text("Set on the machine: " + it.joinToString(", "), style = mono(12f, 500, p.att), modifier = Modifier.padding(top = 4.dp))
            }
            res?.str("post_install")?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(6.dp)); Md(it, p.tx2, 13.5f) }
            val log = HubLogic.logTail(job.str("log"))
            if (log.isNotBlank()) Text(log, style = mono(11f, 400, p.tx2), modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
                .clip(RoundedCornerShape(10.dp)).background(p.s2).padding(10.dp))
        }
    }
}

@Composable
fun ConfirmDialog(title: String, body: @Composable () -> Unit, yes: String, danger: Boolean = false, onYes: () -> Unit, onNo: () -> Unit) {
    val p = P
    HDialog(title, onDismiss = onNo, body = {
            Column { body() }
        }) {
            Btn("Cancel", primary = false, onClick = onNo)
            Btn(yes, danger = danger, onClick = onYes)
        }
}

@Composable
private fun KVLine(k: String, v: String?) {
    if (v.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, style = mono(12f, 500, P.mu), modifier = Modifier.width(96.dp))
        Text(v, style = mono(12f, 500, P.tx2), modifier = Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------- skill detail
@Composable
fun HubSkillScreen(ident: String) {
    val p = P
    val act = LocalAct.current
    val co = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val prof by Mk.profile.collectAsState()
    var d by remember(ident, prof) { mutableStateOf<JSONObject?>(null) }
    var err by remember(ident, prof) { mutableStateOf<String?>(null) }
    var scan by remember(ident) { mutableStateOf<JSONObject?>(null) }
    var scanErr by remember(ident) { mutableStateOf<String?>(null) }
    var job by remember(ident) { mutableStateOf<JSONObject?>(null) }
    var ask by remember { mutableStateOf<String?>(null) }
    var showFindings by remember { mutableStateOf(false) }
    suspend fun load() { runCatching { d = Api.get(HubLogic.withProfile("/v1/hub/skills/preview?identifier=${HubLogic.enc(ident)}", prof)) }.onFailure { err = it.message } }
    LaunchedEffect(ident, prof) { load() }
    LaunchedEffect(ident) { runCatching { scan = Api.get("/v1/hub/skills/scan?identifier=${HubLogic.enc(ident)}") }.onFailure { scanErr = it.message } }
    val o = d
    val inst = o?.optJSONObject("installed")
    val installedLocal = o?.optBoolean("installed_local") == true
    val running = job?.str("state") == "running"
    val gate = HubLogic.gate(scan?.str("policy"), scanErr != null)

    fun act(action: String) {
        co.launch {
            val name = inst?.str("name") ?: o?.str("name").orEmpty()
            val body = JSONObject().put("profile", prof)
            val path: String; val key: String?
            when (action) {
                "install" -> { body.put("identifier", ident); path = "/v1/hub/skills/install"; key = HubLogic.guardKey("skill", "install", ident) }
                "update" -> { body.put("name", name); path = "/v1/hub/skills/update"; key = HubLogic.guardKey("skill", "update", name) }
                else -> { body.put("name", name); path = "/v1/hub/skills/uninstall"; key = null }
            }
            runCatching {
                val r = if (key != null) Mk.guarded(act, key, "${action.replaceFirstChar { it.uppercase() }} $name") { h -> Api.post(path, body, h) } else Api.post(path, body)
                Mk.follow(r.getJSONObject("job")) { job = it }
            }.onSuccess { if (it.str("state") == "ok") { Ui.say(when (action) { "install" -> "Installed $name"; "update" -> "Updated $name"; else -> "Removed $name" }); load() } }
                .onFailure { if (it.message != "Cancelled") job = JSONObject().put("state", "failed").put("error", it.message ?: "failed").put("profile", Mk.shownProfile()) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(o?.str("name") ?: ident.substringAfterLast('/'), ident)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
            if (o == null) item { Loading(err) { err = null; co.launch { load() } } }
            else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TrustPill(o.str("trust_level"))
                        Spacer(Modifier.width(6.dp)); Pill(o.str("source_label").ifBlank { o.str("source") }, p.tx2, p.s3)
                        if (inst != null || installedLocal) { Spacer(Modifier.width(6.dp)); Pill("installed in ${Mk.shownProfile()}", p.good, p.s3) }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (o.str("description").isNotBlank()) Text(o.str("description"), style = sans(15.5f, 400, p.tx2, 22f))
                    Spacer(Modifier.height(10.dp))
                    KVLine("source", o.str("source_label").ifBlank { o.str("source") })
                    KVLine("repo", o.str("repo").takeIf { it != "null" })
                    KVLine("installed", inst?.let { it.str("installed_at").take(16).replace('T', ' ') })
                    KVLine("scan then", inst?.str("scan_verdict"))
                    val tags = o.optJSONArray("tags").strs()
                    if (tags.isNotEmpty()) FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { for (t in tags) Chip(t) }
                    o.str("repo").takeIf { it.startsWith("http") }?.let { r ->
                        Text("Open source", style = sans(13.5f, 600, p.acct), modifier = Modifier.padding(top = 8.dp).clickable { runCatching { uri.openUri(r) } })
                    }
                }
                // security scan
                item {
                    val sc = scan
                    Panel(Modifier.padding(vertical = 12.dp), border = when (gate) { HubLogic.Gate.ALLOW -> p.line; HubLogic.Gate.SCANNING -> p.line; else -> p.bad.copy(alpha = 0.5f) }) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icn(Ic.shield, when (gate) { HubLogic.Gate.ALLOW -> p.good; HubLogic.Gate.SCANNING -> p.mu; else -> p.bad }, 18.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(when (gate) {
                                    HubLogic.Gate.SCANNING -> "Security scan running on the machine…"
                                    HubLogic.Gate.SCAN_FAILED -> "Security scan failed"
                                    HubLogic.Gate.ALLOW -> "Scan: ${sc?.str("verdict")} · install allowed"
                                    HubLogic.Gate.REFUSED -> "Scan: ${sc?.str("verdict")} · Hermes won't install this"
                                }, style = sans(15f, 600, p.tx), modifier = Modifier.weight(1f))
                            }
                            scanErr?.let { Text(it, style = mono(12f, 500, p.bad), modifier = Modifier.padding(top = 6.dp)) }
                            if (sc != null) {
                                Text(HubLogic.findingsLine(sc.counts()) + " · " + sc.str("policy_reason"), style = sans(13f, 400, p.mu, 18f), modifier = Modifier.padding(top = 6.dp))
                                if (sc.str("summary").isNotBlank()) Text(sc.str("summary"), style = sans(13f, 400, p.tx2, 18f), modifier = Modifier.padding(top = 4.dp))
                                val f = sc.optJSONArray("findings").objList()
                                if (f.isNotEmpty()) {
                                    Text(if (showFindings) "Hide findings" else "Show ${f.size} finding" + (if (f.size == 1) "" else "s"), style = sans(13.5f, 600, p.acct),
                                        modifier = Modifier.padding(top = 8.dp).clickable { showFindings = !showFindings })
                                    if (showFindings) for (x in f) Column(Modifier.padding(top = 8.dp)) {
                                        Text("${x.str("severity").uppercase()} · ${x.str("category")} · ${x.str("file")}:${x.optInt("line")}",
                                            style = mono(11.5f, 600, if (x.str("severity") in setOf("critical", "high")) p.bad else p.att))
                                        Text(x.str("description"), style = sans(13f, 400, p.tx2, 18f))
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    JobPanel(job, when (job?.str("action")) { "uninstall" -> "Removing"; "update" -> "Updating"; else -> "Installing" })
                    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (inst == null && !installedLocal) Btn("Install", Modifier.weight(1f), enabled = gate == HubLogic.Gate.ALLOW && !running) { ask = "install" }
                        else if (inst != null) {
                            Btn("Update", Modifier.weight(1f), primary = false, enabled = !running) { ask = "update" }
                            Btn("Uninstall", Modifier.weight(1f), danger = true, enabled = !running) { ask = "uninstall" }
                        } else {
                            Btn("Manage in Extensions", Modifier.weight(1f), primary = false) { Ui.push(Route.ExtR) }
                        }
                    }
                    if (inst == null && !installedLocal && gate == HubLogic.Gate.REFUSED) Text("Hermes' scan policy refuses this skill. There is no override from the phone; review it on the machine if you must.",
                        style = sans(12.5f, 400, p.dim, 17f))
                }
                val files = o.optJSONArray("files").strs()
                if (files.isNotEmpty()) item {
                    Section("Files", files.size.toString())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { for (f in files.take(60)) Chip(f) }
                }
                val md = o.str("skill_md").let { if (it.startsWith("---")) it.substringAfter("\n---", it).removePrefix("\n") else it }
                if (md.isNotBlank()) item { Section("SKILL.md"); Md(md.take(60000)) }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
    when (ask) {
        "install" -> ConfirmDialog("Install ${o?.str("name")}?", {
            Text("Into profile ${Mk.shownProfile()}. Hermes downloads it, scans it again and installs it on the machine.", style = sans(15f, 400, p.tx2, 21f))
            scan?.let { Text("Scan: ${it.str("verdict")} · ${HubLogic.findingsLine(it.counts())}", style = mono(12.5f, 600, p.mu), modifier = Modifier.padding(top = 8.dp)) }
            Text("Source: ${o?.str("source_label")} (${HubLogic.trustLabel(o?.str("trust_level").orEmpty())})", style = mono(12.5f, 500, p.mu))
        }, "Install", onYes = { ask = null; act("install") }, onNo = { ask = null })
        "update" -> ConfirmDialog("Update ${inst?.str("name")}?", {
            Text("Hermes scans the new version first and refuses it unless the scan allows it. Skills you edited locally are skipped.", style = sans(15f, 400, p.tx2, 21f))
        }, "Update", onYes = { ask = null; act("update") }, onNo = { ask = null })
        "uninstall" -> ConfirmDialog("Uninstall ${inst?.str("name")}?", {
            Text("Removes it from profile ${Mk.shownProfile()}. New chats stop seeing it.", style = sans(15f, 400, p.tx2, 21f))
        }, "Uninstall", danger = true, onYes = { ask = null; act("uninstall") }, onNo = { ask = null })
    }
}

// ---------------------------------------------------------------- plugins
@Composable
fun HubPluginsTab() {
    val p = P
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf("discover") }
    var cat by remember { mutableStateOf("all") }
    var retry by remember { mutableStateOf(0) }
    val foreground = Ui.stack.lastOrNull() == Route.HubR
    LaunchedEffect(retry, foreground) {
        if (!foreground) return@LaunchedEffect
        err = null
        runCatching { Api.get("/v1/hub/plugins") }.onSuccess { o -> data = o; o.optJSONArray("entries").objList().forEach { Mk.plugins[it.str("name")] = it } }
            .onFailure { err = it.message }
    }
    val all = data?.optJSONArray("entries").objList()
    val filters = HubLogic.Filters(scope = scope, category = cat, query = q)
    val list = all.filter { e ->
        filters.matches(e.optBoolean("installed"), "", e.str("category"), listOf("name", "title", "description", "maintainer").joinToString(" ") { e.str(it) })
    }.sortedWith(compareBy<JSONObject> { if (it.str("tier") == "official") 0 else 1 }.thenBy { it.str("title").ifBlank { it.str("name") }.lowercase() })
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            RestartBanner()
            HubScopes(scope) { scope = it }
            InputBox(q, { q = it }, "Search ${all.size} catalog plugins", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), maxLines = 1)
            val cats = data?.optJSONArray("categories").strs().filter { c -> all.any { it.str("category") == c } }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("All categories", cat == "all") { cat = "all" }
                for (c in cats) Chip(HubLogic.pretty(c), cat == c) { cat = c }
            }
            Section(if (scope == "discover") "Available plugins" else if (scope == "installed") "Installed plugins" else "All plugins", list.size.toString())
        }
        if (data == null) item { Loading(err) { retry++ } }
        else if (list.isEmpty()) item { Empty("Nothing here", "Try another shelf or word.") }
        items(list, key = { "p:" + it.str("name") }) { e ->
            Row(Modifier.fillMaxWidth().clickable { Ui.push(Route.HubPluginR(e.str("name"))) }.padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.str("title").ifBlank { e.str("name") }, style = sans(15f, 600, p.tx), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(6.dp)); Pill(e.str("tier"), if (e.str("tier") == "official") p.acct else p.tx2, if (e.str("tier") == "official") p.accd else p.s3)
                        if (e.optBoolean("installed")) { Spacer(Modifier.width(6.dp)); Pill(if (e.optBoolean("update_available")) "update" else "installed", if (e.optBoolean("update_available")) p.att else p.good, p.s3) }
                    }
                    Text(listOf(e.str("maintainer"), HubLogic.pretty(e.str("category")), e.str("version")).filter { it.isNotBlank() }.joinToString(" · "), style = mono(11f, 500, p.dim))
                    Text(e.str("description"), style = sans(13f, 400, p.mu, 18f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icn(Ic.chevR, p.mu, 18.dp)
            }
        }
        data?.optJSONArray("removed").objList().takeIf { scope != "discover" && it.isNotEmpty() }?.let { rm ->
            item { Section("Pulled from the catalog", rm.size.toString()) }
            items(rm) { r -> Text("${r.str("name")}: ${r.str("reason")}", style = sans(12.5f, 400, p.dim, 17f), modifier = Modifier.padding(horizontal = 20.dp, vertical = 3.dp)) }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
fun HubPluginScreen(name: String) {
    val p = P
    val gateway by Mk.gateway.collectAsState()
    val act = LocalAct.current
    val co = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var e by remember(name) { mutableStateOf(Mk.plugins[name]) }
    var job by remember(name) { mutableStateOf<JSONObject?>(null) }
    var ask by remember { mutableStateOf<String?>(null) }
    var loadErr by remember(name) { mutableStateOf<String?>(null) }
    var retry by remember(name) { mutableStateOf(0) }
    suspend fun reload() {
        loadErr = null
        try {
            val entries = Api.get("/v1/hub/plugins").optJSONArray("entries").objList()
            entries.forEach { Mk.plugins[it.str("name")] = it }
            e = entries.firstOrNull { it.str("name") == name }
            if (e == null) { Mk.plugins.remove(name); loadErr = "This plugin is no longer in the catalog." }
        } catch (ex: kotlinx.coroutines.CancellationException) { throw ex }
        catch (ex: Exception) { loadErr = "Could not load plugin details: ${ex.message ?: "Connection failed"}" }
    }
    LaunchedEffect(name, retry) { if (e == null || retry > 0) reload() }
    val o = e
    val running = job?.str("state") == "running"
    // An installed catalog plugin's directory name can differ from the catalog name (hermes-plugin-x vs x).
    val local = o?.str("installed_name")?.ifBlank { null } ?: name

    fun run(action: String, accept: Boolean = false) {
        co.launch {
            val path = "/v1/hub/plugins/$action"
            val body = JSONObject().put("name", if (action == "install") name else local).apply { if (accept) put("accept_capabilities", true) }
            runCatching {
                val r = Mk.guarded(act, HubLogic.guardKey("plugin", action, body.str("name"), accept), "${HubLogic.pretty(action)} plugin $name") { h -> Api.post(path, body, h) }
                Mk.follow(r.getJSONObject("job")) { job = it }
            }.onSuccess { j ->
                if (j.str("state") == "ok") {
                    val res = j.optJSONObject("result")
                    if (res?.optBoolean("restart_required") == true || action != "install") Ctl.needRestart.value = true
                    reload()
                } else if (j.str("state") == "consent") ask = "consent"
            }.onFailure { if (it.message != "Cancelled") job = JSONObject().put("state", "failed").put("error", it.message ?: "failed").put("profile", Mk.gateway.value) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(o?.str("title")?.ifBlank { null } ?: name, "plugin · " + name)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
            if (loadErr != null) item { ErrorPanel(loadErr!!) { loadErr = null; retry++ } }
            if (o == null) { if (loadErr == null) item { Skeleton() } }
            else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(o.str("tier"), if (o.str("tier") == "official") p.acct else p.tx2, if (o.str("tier") == "official") p.accd else p.s3)
                        Spacer(Modifier.width(6.dp)); Pill(HubLogic.pretty(o.str("category")), p.tx2, p.s3)
                        if (o.optBoolean("installed")) { Spacer(Modifier.width(6.dp)); Pill(o.str("runtime_status").ifBlank { "installed" }, p.good, p.s3) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(o.str("description"), style = sans(15.5f, 400, p.tx2, 22f))
                    Spacer(Modifier.height(10.dp))
                    KVLine("maintainer", o.str("maintainer"))
                    KVLine("version", o.str("version"))
                    KVLine("pinned", o.str("sha_short"))
                    KVLine("installed", o.str("installed_sha").take(7).ifBlank { null })
                    KVLine("needs", o.str("requires_hermes").ifBlank { null }?.let { "Hermes $it" })
                    KVLine("platforms", o.optJSONArray("platforms").strs().joinToString(", ").ifBlank { null })
                    KVLine("adds", o.str("capability_summary"))
                    val caps = o.optJSONObject("capabilities")
                    KVLine("tools", caps?.optJSONArray("provides_tools").strs().joinToString(", ").ifBlank { null })
                    KVLine("hooks", caps?.optJSONArray("provides_hooks").strs().joinToString(", ").ifBlank { null })
                    KVLine("middleware", caps?.optJSONArray("provides_middleware").strs().joinToString(", ").ifBlank { null })
                    KVLine("env", caps?.optJSONArray("requires_env").strs().joinToString(", ").ifBlank { null })
                    o.optJSONArray("known_issues").strs().forEach { Text("⚠ $it", style = sans(13f, 400, p.att, 18f), modifier = Modifier.padding(top = 4.dp)) }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        o.str("repo").takeIf { it.startsWith("https://") }?.let { r -> Text("Repository", style = sans(13.5f, 600, p.acct), modifier = Modifier.clickable { runCatching { uri.openUri(r) } }) }
                        o.str("docs_url").takeIf { it.startsWith("https://") }?.let { r -> Text("Docs", style = sans(13.5f, 600, p.acct), modifier = Modifier.clickable { runCatching { uri.openUri(r) } }) }
                    }
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    Text("Plugins run inside Hermes with its permissions. Catalog entries are reviewed and pinned to one commit; installs refuse anything on the kill list.",
                        style = sans(12.5f, 400, p.dim, 17f))
                    JobPanel(job, when (job?.str("action")) { "remove" -> "Removing"; "update" -> "Updating"; else -> "Installing" })
                    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!o.optBoolean("installed")) Btn("Install", Modifier.weight(1f), enabled = !running) { ask = "install" }
                        else {
                            if (o.optBoolean("update_available")) Btn("Update", Modifier.weight(1f), primary = false, enabled = !running) { ask = "update" }
                            Btn("Remove", Modifier.weight(1f), danger = true, enabled = !running) { ask = "remove" }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
    val caps = o?.str("capability_summary").orEmpty()
    when (ask) {
        "install" -> ConfirmDialog("Install ${o?.str("title")?.ifBlank { null } ?: name}?", {
            Text("Installs at the pinned commit ${o?.str("sha_short")} into $gateway and turns it on.", style = sans(15f, 400, p.tx2, 21f))
            if (caps.isNotBlank()) Text("Adds: $caps", style = mono(12.5f, 600, p.mu), modifier = Modifier.padding(top = 8.dp))
        }, "Install", onYes = { ask = null; run("install") }, onNo = { ask = null })
        "update" -> ConfirmDialog("Update $name?", { Text("Moves it to the catalog's current pin. If that adds tools or hooks, you'll be asked again.", style = sans(15f, 400, p.tx2, 21f)) },
            "Update", onYes = { ask = null; run("update") }, onNo = { ask = null })
        "consent" -> ConfirmDialog("The update widens $name", {
            val res = job?.optJSONObject("result")
            for (l in res?.optJSONArray("delta_lines").strs()) Text(l, style = mono(12.5f, 500, p.att))
            Text("Accept and update?", style = sans(15f, 400, p.tx2, 21f), modifier = Modifier.padding(top = 8.dp))
        }, "Accept", onYes = { ask = null; run("update", accept = true) }, onNo = { ask = null })
        "remove" -> ConfirmDialog("Remove $name?", { Text("Deletes the plugin from the machine. Hermes refuses this while the gateway is running; then remove it from the CLI.", style = sans(15f, 400, p.tx2, 21f)) },
            "Remove", danger = true, onYes = { ask = null; run("remove") }, onNo = { ask = null })
    }
}

// ---------------------------------------------------------------- mcp
@Composable
fun HubMcpTab() {
    val p = P
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf("discover") }
    var retry by remember { mutableStateOf(0) }
    val foreground = Ui.stack.lastOrNull() == Route.HubR
    LaunchedEffect(retry, foreground) {
        if (!foreground) return@LaunchedEffect
        err = null
        runCatching { Api.get("/v1/hub/mcp") }.onSuccess { o -> data = o; o.optJSONArray("entries").objList().forEach { Mk.mcp[it.str("name")] = it } }
            .onFailure { err = it.message }
    }
    val all = data?.optJSONArray("entries").objList()
    val filters = HubLogic.Filters(scope = scope, query = q)
    val list = all.filter { filters.matches(it.optBoolean("installed"), "", "", it.str("name") + " " + it.str("description")) }
        .sortedBy { it.str("name").lowercase() }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            RestartBanner()
            HubScopes(scope) { scope = it }
            InputBox(q, { q = it }, "Search ${all.size} MCP servers", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), maxLines = 1)
            Text("The Nous-approved MCP catalog. Each entry shows exactly what it runs before you install it.", style = sans(12.5f, 400, p.dim, 17f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Section(if (scope == "discover") "Available servers" else if (scope == "installed") "Installed servers" else "All servers", list.size.toString())
        }
        if (data == null) item { Loading(err) { retry++ } }
        else if (list.isEmpty()) item {
            Empty(if (q.isBlank()) "No MCP servers" else "No matching MCP servers",
                if (q.isBlank()) "The catalog has no servers available." else "Try another search word.")
        }
        items(list, key = { "m:" + it.str("name") }) { e ->
            Row(Modifier.fillMaxWidth().clickable { Ui.push(Route.HubMcpR(e.str("name"))) }.padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.str("name"), style = sans(15f, 600, p.tx))
                        Spacer(Modifier.width(6.dp)); Pill(e.str("transport"), p.tx2, p.s3)
                        if (e.str("auth_type") != "none") { Spacer(Modifier.width(6.dp)); Pill(e.str("auth_type"), p.att, p.attd) }
                        if (e.optBoolean("installed")) { Spacer(Modifier.width(6.dp)); Pill(if (e.optBoolean("enabled")) "installed" else "off", p.good, p.s3) }
                    }
                    Text(e.str("description"), style = sans(13f, 400, p.mu, 18f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icn(Ic.chevR, p.mu, 18.dp)
            }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun SecretField(v: String, on: (String) -> Unit, ph: String, secret: Boolean) {
    val p = P
    BasicTextField(v, on, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text, autoCorrectEnabled = false),
        textStyle = mono(14f, 500, p.tx), cursorBrush = SolidColor(p.acct),
        decorationBox = { inner ->
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(p.s2).border(1.dp, p.line, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 11.dp)) {
                if (v.isEmpty()) Text(ph, style = mono(14f, 400, p.dim)); inner()
            }
        })
}

@Composable
fun HubMcpScreen(name: String) {
    val p = P
    val gateway by Mk.gateway.collectAsState()
    val act = LocalAct.current
    val co = rememberCoroutineScope()
    var e by remember(name) { mutableStateOf(Mk.mcp[name]) }
    var job by remember(name) { mutableStateOf<JSONObject?>(null) }
    var ask by remember { mutableStateOf<String?>(null) }
    val env = remember(name) { mutableStateMapOf<String, String>() }
    var loadErr by remember(name) { mutableStateOf<String?>(null) }
    var retry by remember(name) { mutableStateOf(0) }
    suspend fun reload() {
        loadErr = null
        try {
            val entries = Api.get("/v1/hub/mcp").optJSONArray("entries").objList()
            entries.forEach { Mk.mcp[it.str("name")] = it }
            e = entries.firstOrNull { it.str("name") == name }
            if (e == null) { Mk.mcp.remove(name); loadErr = "This MCP server is no longer in the catalog." }
        } catch (ex: kotlinx.coroutines.CancellationException) { throw ex }
        catch (ex: Exception) { loadErr = "Could not load MCP server details: ${ex.message ?: "Connection failed"}" }
    }
    LaunchedEffect(name, retry) { if (e == null || retry > 0) reload() }
    val o = e
    val running = job?.str("state") == "running"
    val req = o?.optJSONArray("required_env").objList()
    val missing = HubLogic.missingEnv(req.map { it.str("name") to it.optBoolean("required", true) }, env)

    fun run(action: String) {
        co.launch {
            val body = JSONObject().put("name", name)
            if (action == "install") body.put("env", JSONObject(env.filterValues { it.isNotBlank() }.toMap()))
            val path = if (action == "install") "/v1/hub/mcp/install" else "/v1/hub/mcp/uninstall"
            runCatching {
                val r = Mk.guarded(act, HubLogic.guardKey("mcp", action, name), "${if (action == "install") "Install" else "Remove"} MCP $name") { h -> Api.post(path, body, h) }
                Mk.follow(r.getJSONObject("job")) { job = it }
            }.onSuccess { j -> if (j.str("state") == "ok") { Ctl.needRestart.value = true; env.clear(); reload() } }
                .onFailure { if (it.message != "Cancelled") job = JSONObject().put("state", "failed").put("error", it.message ?: "failed").put("profile", Mk.gateway.value) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(name, "MCP server · " + (o?.str("transport") ?: ""))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
            if (loadErr != null) item { ErrorPanel(loadErr!!) { loadErr = null; retry++ } }
            if (o == null) { if (loadErr == null) item { Skeleton() } }
            else {
                item {
                    Text(o.str("description"), style = sans(15.5f, 400, p.tx2, 22f))
                    Spacer(Modifier.height(10.dp))
                    KVLine("source", o.str("source"))
                    KVLine("auth", o.str("auth_type"))
                    KVLine("url", o.str("url").takeIf { it != "null" })
                    KVLine("runs", (listOf(o.str("command")) + o.optJSONArray("args").strs()).filter { it.isNotBlank() && it != "null" }.joinToString(" ").ifBlank { null })
                    KVLine("clones", o.str("install_url").takeIf { it != "null" })
                    val boot = o.optJSONArray("bootstrap").strs()
                    if (boot.isNotEmpty()) {
                        Text("Build steps that run on the machine:", style = sans(13f, 600, p.att), modifier = Modifier.padding(top = 8.dp))
                        Text(boot.joinToString("\n"), style = mono(11.5f, 500, p.tx2), modifier = Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.s2).padding(10.dp))
                    }
                    if (o.str("auth_type") == "oauth") Text("Uses OAuth. Finish sign-in on the machine after installing.", style = sans(13f, 400, p.att, 18f), modifier = Modifier.padding(top = 8.dp))
                }
                if (!o.optBoolean("installed") && req.isNotEmpty()) {
                    item { Section("Credentials", "saved to the machine's .env") }
                    items(req, key = { "env:" + it.str("name") }) { r ->
                        Column(Modifier.padding(vertical = 5.dp)) {
                            Text(r.str("name") + if (r.optBoolean("required", true)) "" else " (optional)", style = mono(12f, 600, p.mu))
                            if (r.str("prompt").isNotBlank()) Text(r.str("prompt"), style = sans(12.5f, 400, p.dim, 17f))
                            Spacer(Modifier.height(4.dp))
                            SecretField(env[r.str("name")].orEmpty(), { env[r.str("name")] = it }, r.str("name"), r.optBoolean("secret", true))
                        }
                    }
                }
                item {
                    JobPanel(job, if (job?.str("action") == "remove") "Removing" else "Installing")
                    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!o.optBoolean("installed")) Btn("Install", Modifier.weight(1f), enabled = !running && missing.isEmpty()) { ask = "install" }
                        else Btn("Remove", Modifier.weight(1f), danger = true, enabled = !running) { ask = "remove" }
                    }
                    if (!o.optBoolean("installed") && missing.isNotEmpty()) Text("Fill in " + missing.joinToString(", ") + " first.", style = sans(12.5f, 400, p.dim, 17f))
                    if (o.str("post_install").isNotBlank() && o.optBoolean("installed")) { Spacer(Modifier.height(8.dp)); Md(o.str("post_install"), p.tx2, 13.5f) }
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
    when (ask) {
        "install" -> ConfirmDialog("Install $name?", {
            Text("Adds it to ${gateway}'s config" + (if (o?.optBoolean("needs_install") == true) ", clones it and runs its build steps on the machine" else "") +
                ". Restart Hermes to connect it.", style = sans(15f, 400, p.tx2, 21f))
        }, "Install", onYes = { ask = null; run("install") }, onNo = { ask = null })
        "remove" -> ConfirmDialog("Remove $name?", { Text("Takes it out of the config and deletes its clone, if any.", style = sans(15f, 400, p.tx2, 21f)) },
            "Remove", danger = true, onYes = { ask = null; run("remove") }, onNo = { ask = null })
    }
}
