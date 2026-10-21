@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.avifenesh.hermes

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Model / reasoning / fast per chat, plus the restart-needed flag shared by Settings and Skills & plugins. */
object Ctl {
    val models = MutableStateFlow<JSONObject?>(null)
    val loadingModels = MutableStateFlow(false)
    val chat = MutableStateFlow<Map<String, JSONObject>>(emptyMap())
    val needRestart = MutableStateFlow(false)

    suspend fun loadModels(refresh: Boolean = false) {
        if (!refresh && models.value != null) return
        loadingModels.value = true
        try { models.value = Api.get("/v1/models" + if (refresh) "?refresh=1" else "") } finally { loadingModels.value = false }
    }

    suspend fun loadChat(cid: String): JSONObject {
        val o = Api.get("/v1/chats/${Uri.encode(cid)}/model")
        chat.update { it + (cid to o) }
        return o
    }

    suspend fun apply(cid: String, body: JSONObject): JSONObject {
        val o = Api.post("/v1/chats/${Uri.encode(cid)}/model", body)
        o.optJSONObject("state")?.let { s -> chat.update { it + (cid to s) } }
        return o
    }

    suspend fun confirm(cid: String, id: String, choice: String): JSONObject {
        val o = Api.post("/v1/chats/${Uri.encode(cid)}/confirm", JSONObject().put("id", id).put("choice", choice))
        o.optJSONObject("state")?.let { s -> chat.update { it + (cid to s) } }
        return o
    }

    fun providerName(slug: String): String =
        models.value?.optJSONArray("providers").objs().firstOrNull { it.str("slug") == slug }?.str("name")?.ifBlank { null } ?: slug

    fun caps(provider: String, model: String): JSONObject? =
        models.value?.optJSONArray("providers").objs().firstOrNull { it.str("slug") == provider }?.optJSONObject("capabilities")?.optJSONObject(model)
}

// ---------------------------------------------------------------- model chip + sheet
@Composable
fun ModelChip(model: String, reasoning: String, fast: Boolean, changed: Boolean = false, busy: Boolean = false, onClick: () -> Unit) {
    val p = P
    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(if (changed) p.accd else p.s2).border(1.dp, if (changed) p.acct.copy(alpha = 0.5f) else p.line, RoundedCornerShape(14.dp))
        .clickable(enabled = !busy, onClick = onClick).padding(start = 9.dp, end = 7.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (busy) LiveDot(8.dp) else Icn(Ic.cpu, p.acct, 14.dp)
        Spacer(Modifier.width(6.dp))
        Text(shortModel(model).ifBlank { "model" }, style = mono(12.5f, 600, p.tx), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
        if (reasoning.isNotBlank()) Text(" · $reasoning", style = mono(12.5f, 500, p.mu), maxLines = 1)
        if (fast) { Spacer(Modifier.width(4.dp)); Icn(Ic.zap, p.att, 13.dp) }
        Spacer(Modifier.width(2.dp))
        Icn(Ic.chevD, p.mu, 14.dp)
    }
}

@Composable
private fun FastChip(fast: Boolean, busy: Boolean = false, onFast: (Boolean) -> Unit) {
    val p = P
    Text(if (fast) "ϟ Fast on" else "ϟ Fast off", style = mono(12.5f, 600, if (fast) p.acct else p.mu),
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (fast) p.accd else p.s2)
            .toggleable(value = fast, enabled = !busy, role = Role.Switch, onValueChange = onFast)
            .padding(horizontal = 10.dp, vertical = 14.dp))
}

private val EFFORTS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

@Composable
fun ModelSheet(
    title: String, cur: JSONObject?, showReasoning: Boolean, showScope: Boolean, scope: String, onScope: (String) -> Unit,
    onModel: (String, String) -> Unit, onReasoning: (String) -> Unit, onFast: ((Boolean) -> Unit)?, onDismiss: () -> Unit,
) {
    val p = P
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val models by Ctl.models.collectAsState()
    val loading by Ctl.loadingModels.collectAsState()
    val co = rememberCoroutineScope()
    var err by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    val open = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(Unit) { runCatching { Ctl.loadModels() }.onFailure { err = it.message } }
    val curModel = cur?.str("model").orEmpty()
    val curProv = cur?.str("provider").orEmpty()
    val provs = models?.optJSONArray("providers").objs()
    val caps = cur?.optJSONObject("caps") ?: Ctl.caps(curProv, curModel)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = p.s1,
        dragHandle = { Box(Modifier.padding(top = 10.dp).size(40.dp, 4.dp).clip(CircleShape).background(p.s4)) }) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding().navigationBarsPadding()) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = serif(34f, p.tx), modifier = Modifier.weight(1f))
                    if (loading) Text("loading…", style = mono(11.5f, 500, p.mu))
                    IconBtn(Ic.refresh, p.tx2) { co.launch { err = null; runCatching { Ctl.loadModels(refresh = true) }.onFailure { err = it.message } } }
                }
                if (curModel.isNotBlank()) Text("now $curModel · ${Ctl.providerName(curProv)}", style = mono(12f, 500, p.mu), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp))
            }
            if (showScope) item {
                Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Seg(listOf("chat" to "This chat", "global" to "Default for all"), scope, onScope)
                }
                Text(if (scope == "global") "Saved to config. New chats start here too." else "Only this conversation. Your default stays.",
                    style = sans(12.5f, 400, p.mu), modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
            }
            if (showReasoning && caps?.optBoolean("reasoning", true) != false) item {
                Text("REASONING", style = mono(11f, 600, p.mu), modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (e in EFFORTS) Chip(e, e == cur?.str("reasoning")) { onReasoning(e) }
                }
            }
            if (onFast != null && caps?.optBoolean("fast") == true) item {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icn(Ic.zap, p.att, 16.dp); Spacer(Modifier.width(8.dp))
                        Text("Fast mode", style = sans(15f, 600, p.tx))
                    }
                    Text("Priority processing, billed at a premium", style = sans(12.5f, 400, p.mu))
                    Text(if (cur?.optBoolean("fast") == true) "Fast mode on" else "Fast mode off",
                        style = mono(12f, 600, p.acct),
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(p.accd)
                            .toggleable(value = cur?.optBoolean("fast") == true, role = Role.Switch, onValueChange = onFast)
                            .padding(horizontal = 12.dp, vertical = 14.dp))
                }
            }
            item { InputBox(q, { q = it }, "Search models or providers", Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp), maxLines = 1) }
            err?.let { e -> item { Text(e, style = sans(13.5f, 400, p.bad), modifier = Modifier.padding(20.dp)) } }
            if (models == null && err == null) item { Text("Fetching providers…", style = mono(13f, 500, p.mu), modifier = Modifier.padding(20.dp)) }
            if (q.isNotBlank()) {
                val hits = provs.flatMap { pr -> pr.optJSONArray("models").strs().filter { it.contains(q.trim(), true) || pr.str("name").contains(q.trim(), true) }.map { pr to it } }.take(120)
                if (hits.isEmpty() && models != null) item { Text("No model matches \"$q\".", style = sans(14f, 400, p.mu), modifier = Modifier.padding(20.dp)) }
                items(hits) { (pr, m) ->
                    ModelRow(m, pr.str("name"), m == curModel && pr.str("slug") == curProv, pr.optJSONObject("capabilities")?.optJSONObject(m)) { onModel(m, pr.str("slug")) }
                }
            } else {
                val recent = models?.optJSONArray("recent").objs().mapNotNull { r ->
                    val m = r.str("model")
                    val hit = provs.firstOrNull { it.str("slug") == r.str("provider") && m in it.optJSONArray("models").strs() }
                        ?: provs.firstOrNull { m in it.optJSONArray("models").strs() }
                    hit?.let { Triple(m, it, r) }
                }.filter { !(it.first == curModel && it.second.str("slug") == curProv) }.take(5)
                if (recent.isNotEmpty()) {
                    item { Section("Recent", null) }
                    items(recent) { (m, pr, _) -> ModelRow(m, pr.str("name"), false, pr.optJSONObject("capabilities")?.optJSONObject(m)) { onModel(m, pr.str("slug")) } }
                }
                if (provs.isNotEmpty()) item { Section("Providers", provs.size.toString()) }
                provs.forEachIndexed { pi, pr ->
                    val slug = pr.str("slug")
                    val isOpen = open[slug] ?: (slug == curProv)
                    item(key = "h$pi/$slug") {
                        Row(Modifier.fillMaxWidth().clickable { open[slug] = !isOpen }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(pr.str("name").ifBlank { slug }, style = sans(15f, 600, if (slug == curProv) p.acct else p.tx), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(8.dp))
                            Text(pr.optInt("total_models", pr.optJSONArray("models")?.length() ?: 0).toString(), style = mono(11.5f, 500, p.mu))
                            Spacer(Modifier.weight(1f))
                            Icn(if (isOpen) Ic.chevU else Ic.chevD, p.mu, 16.dp)
                        }
                    }
                    if (isOpen) itemsIndexed(pr.optJSONArray("models").strs(), key = { i, _ -> "m$pi/$slug/$i" }) { _, m ->
                        ModelRow(m, null, m == curModel && slug == curProv, pr.optJSONObject("capabilities")?.optJSONObject(m)) { onModel(m, slug) }
                    }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
fun ModelRow(model: String, provider: String?, selected: Boolean, caps: JSONObject?, onClick: () -> Unit) {
    val p = P
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).background(if (selected) p.accd else androidx.compose.ui.graphics.Color.Transparent)
        .padding(start = 32.dp, end = 18.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(model, style = mono(13.5f, if (selected) 600 else 500, if (selected) p.acct else p.tx), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (provider != null) Text(provider, style = sans(12f, 400, p.mu), maxLines = 1)
        }
        if (caps?.optBoolean("fast") == true) { Spacer(Modifier.width(6.dp)); Icn(Ic.zap, p.mu, 13.dp) }
        if (selected) { Spacer(Modifier.width(8.dp)); Icn(Ic.check, p.acct, 16.dp) }
    }
}

/** Composer control for an existing chat: shows the chat's effective model and switches it through the real /model. */
@Composable
fun ChatModelControl(cid: String) {
    val p = P
    val st = Ctl.chat.collectAsState().value[cid]
    val meta by Hub.meta.collectAsState()
    val co = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var scope by remember(cid) { mutableStateOf("chat") }
    var confirm by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(cid) { runCatching { Ctl.loadChat(cid) } }
    val model = st?.str("model")?.ifBlank { null } ?: meta.str("model")
    fun run(body: JSONObject, done: String) {
        if (busy) return
        busy = true
        if (scope == "global" && !body.has("scope")) body.put("scope", "global")
        co.launch {
            try {
                val o = Ctl.apply(cid, body)
                val c = o.optJSONObject("confirm")
                when {
                    c != null -> confirm = c
                    o.optBoolean("ok") -> Ui.say(done)
                    else -> Ui.say(Hub.plain(o.str("error")).take(220))
                }
            } catch (e: Exception) { Ui.say(e.message) } finally { busy = false }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ModelChip(model, st?.str("reasoning").orEmpty(), st?.optBoolean("fast") == true, st?.str("scope") == "chat", busy) { open = true }
        if (st?.optJSONObject("caps")?.optBoolean("fast") == true) FastChip(st.optBoolean("fast"), busy) { f ->
            run(JSONObject().put("fast", f).put("scope", "chat"), if (f) "Fast mode on for this chat" else "Fast mode off for this chat")
        }
    }
    if (open) ModelSheet("Model", st ?: JSONObject().put("model", model), true, true, scope, { scope = it },
        onModel = { m, pr -> open = false; run(JSONObject().put("model", m).put("provider", pr), "Switched to ${shortModel(m)}") },
        onReasoning = { e -> run(JSONObject().put("reasoning", e), "Reasoning: $e") },
        onFast = { f -> run(JSONObject().put("fast", f), if (f) "Fast mode on" else "Fast mode off") },
        onDismiss = { open = false })
    confirm?.let { c ->
        HDialog("Switch model?", onDismiss = { confirm = null }, body = {
                Text(Hub.plain(c.str("message")).substringBefore("Text fallback").trim().take(600), style = sans(14.5f, 400, p.tx2, 21f))
            }) {
                Btn("Keep current", primary = false) { confirm = null; co.launch { runCatching { Ctl.confirm(cid, c.str("id"), "cancel") } } }
                Btn("Switch") {
                confirm = null; busy = true
                co.launch { runCatching { Ctl.confirm(cid, c.str("id"), "once") }.onSuccess { Ui.say(if (it.optBoolean("ok")) "Switched" else Hub.plain(it.str("reply")).take(200)) }.onFailure { Ui.say(it.message) }; busy = false }
            }
            }
    }
}

/** Composer control for a chat that doesn't exist yet: the pick is applied right after the chat is created. */
@Composable
fun ComposeModelControl(pick: JSONObject?, onPick: (JSONObject?) -> Unit) {
    val base = Ctl.chat.collectAsState().value[NEW_CHAT]
    val meta by Hub.meta.collectAsState()
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { Ctl.loadChat(NEW_CHAT) } }
    val model = pick?.str("model")?.ifBlank { null } ?: base?.str("model")?.ifBlank { null } ?: meta.str("model")
    val provider = pick?.str("provider")?.ifBlank { null } ?: base?.str("provider").orEmpty()
    val reasoning = pick?.str("reasoning")?.ifBlank { null } ?: base?.str("reasoning").orEmpty()
    val fast = if (pick?.has("fast") == true) pick.optBoolean("fast") else base?.optBoolean("fast") == true
    val caps = if (base != null && model == base.str("model") && provider == base.str("provider")) base.optJSONObject("caps") else Ctl.caps(provider, model)
    val cur = JSONObject().put("model", model).put("provider", provider).put("reasoning", reasoning).put("fast", fast).put("caps", caps)
    fun upd(f: (JSONObject) -> Unit) { val n = JSONObject((pick ?: JSONObject()).toString()); f(n); onPick(n) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ModelChip(model, reasoning, fast, pick != null) { open = true }
        if (caps?.optBoolean("fast") == true) FastChip(fast) { f -> upd { it.put("fast", f) } }
    }
    if (open) ModelSheet("Model", cur, true, true, pick?.str("scope")?.ifBlank { null } ?: "chat", { s -> upd { it.put("scope", s) } },
        onModel = { m, pr -> upd { it.put("model", m).put("provider", pr) }; open = false },
        onReasoning = { e -> upd { it.put("reasoning", e) } },
        onFast = { f -> upd { it.put("fast", f) } },
        onDismiss = { open = false })
}

const val NEW_CHAT = "_new"

// ---------------------------------------------------------------- restart
@Composable
fun RestartDialog(onDismiss: () -> Unit) {
    val p = P
    val co = rememberCoroutineScope()
    HDialog("Restart Hermes?", onDismiss = onDismiss, body = {
            Text("Running work drains first, then the gateway reloads config and plugins. The app reconnects on its own.", style = sans(15f, 400, p.tx2, 21f))
        }) {
            Btn("Cancel", primary = false, onClick = onDismiss)
            Btn("Restart") {
            onDismiss()
            co.launch { runCatching { Api.post("/v1/restart") }.onSuccess { Ctl.needRestart.value = false; Ui.say("Restarting Hermes…") }.onFailure { Ui.say(it.message) } }
        }
        }
}

@Composable
fun RestartBanner() {
    val p = P
    val need by Ctl.needRestart.collectAsState()
    var ask by remember { mutableStateOf(false) }
    if (!need) return
    Panel(Modifier.padding(top = 8.dp, bottom = 4.dp), border = p.att.copy(alpha = 0.45f), bg = p.attd) {
        Row(Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icn(Ic.power, p.att, 18.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Restart to apply", style = sans(15f, 600, p.tx))
                Text("Some changes load when Hermes restarts.", style = sans(12.5f, 400, p.tx2))
            }
            Btn("Restart", primary = false) { ask = true }
        }
    }
    if (ask) RestartDialog { ask = false }
}

@Composable
fun NavRow(icon: ImageVector, title: String, sub: String, tint: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) {
    val p = P
    ListRow({ Icn(icon, tint ?: p.acct, 21.dp) }, title, sub, { Icn(Ic.chevR, p.mu, 18.dp) }, onClick, titleColor = tint)
}

// ---------------------------------------------------------------- settings
private fun shown(f: JSONObject, v: Any?): String {
    val key = f.str("key")
    return when {
        v == null || v == JSONObject.NULL || v.toString().isEmpty() -> "default"
        v == false && key.endsWith("reasoning_effort") -> "none"
        key == "model.default" -> shortModel(v.toString())
        else -> v.toString() + f.str("unit").let { if (it.isNotBlank()) " $it" else "" }
    }
}

private fun labelOf(f: JSONObject): String = f.str("label").ifBlank {
    f.str("key").substringAfterLast('.').replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
fun SettingField(f: JSONObject, showKey: Boolean = false) {
    val p = P
    val co = rememberCoroutineScope()
    val act = LocalAct.current
    val key = f.str("key")
    val type = f.str("type")
    var v by remember(f.toString()) { mutableStateOf(f.opt("value").takeIf { it != JSONObject.NULL }) }
    var prov by remember(f.toString()) { mutableStateOf(f.str("provider")) }
    var edit by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val help = f.str("help").takeIf { it.isNotBlank() && !it.contains("→") }.orEmpty()
    fun save(nv: Any?, provider: String? = null) {
        busy = true
        co.launch {
            try {
                val b = JSONObject().put("key", key).put("value", nv ?: JSONObject.NULL)
                if (provider != null) b.put("provider", provider)
                val o = Guard.call(act, "config", key, nv, "Change ${labelOf(f)}") { h -> Api.put("/v1/settings", b, h) }
                if (key == "model.default") { v = o.str("model"); prov = o.str("provider"); Ctl.chat.value = emptyMap(); runCatching { Hub.refreshMe() } }
                else v = o.opt("value").takeIf { it != JSONObject.NULL }
                if (f.optBoolean("restart")) Ctl.needRestart.value = true
                Ui.say("Saved")
            } catch (e: Exception) { if (e.message != "Cancelled") Ui.say(e.message) } finally { busy = false }
        }
    }
    Row(Modifier.fillMaxWidth().clickable(enabled = type != "bool" && !busy) { edit = true }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(labelOf(f), style = sans(15f, 600, p.tx), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (f.optBoolean("restart")) { Spacer(Modifier.width(6.dp)); Pill("restart", p.mu, p.s3) }
            }
            if (showKey) Text(key, style = mono(11f, 500, p.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (help.isNotBlank()) Text(help, style = sans(12.5f, 400, p.mu, 17f), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        when (type) {
            "bool" -> Toggle(v == true, { save(it) }, enabled = !busy)
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) { LiveDot(8.dp); Spacer(Modifier.width(6.dp)) }
                Text(shown(f, v), style = mono(13f, 500, if (v == null) p.mu else p.tx2), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                Icn(Ic.chevR, p.mu, 16.dp)
            }
        }
    }
    if (edit) when (type) {
        "model" -> ModelSheet("Default model", JSONObject().put("model", v?.toString().orEmpty()).put("provider", prov), false, false, "global", {},
            onModel = { m, pr -> edit = false; save(m, pr) }, onReasoning = {}, onFast = null, onDismiss = { edit = false })
        "select" -> OptionSheet(labelOf(f), f.optJSONArray("options").strs(), if (v == false) "none" else v?.toString().orEmpty(),
            onPick = { edit = false; save(it) }, onDismiss = { edit = false })
        else -> EditDialog(labelOf(f), v?.toString().orEmpty(), type == "number", onSave = { edit = false; save(it) }, onDismiss = { edit = false })
    }
}

@Composable
fun OptionSheet(title: String, options: List<String>, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val p = P
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var q by remember { mutableStateOf("") }
    val list = options.filter { q.isBlank() || it.contains(q.trim(), true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = p.s1,
        dragHandle = { Box(Modifier.padding(top = 10.dp).size(40.dp, 4.dp).clip(CircleShape).background(p.s4)) }) {
        LazyColumn(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            item { Text(title, style = serif(30f, p.tx), modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 8.dp)) }
            if (options.size > 12) item { InputBox(q, { q = it }, "Search", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), maxLines = 1) }
            items(list) { o ->
                val sel = o == current
                Row(Modifier.fillMaxWidth().clickable { onPick(o) }.background(if (sel) p.accd else androidx.compose.ui.graphics.Color.Transparent)
                    .padding(horizontal = 22.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(o.ifEmpty { "default" }, style = sans(15.5f, if (sel) 600 else 500, if (sel) p.acct else if (o.isEmpty()) p.mu else p.tx), modifier = Modifier.weight(1f))
                    if (sel) Icn(Ic.check, p.acct, 17.dp)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun EditDialog(title: String, initial: String, numeric: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val p = P
    var t by remember { mutableStateOf(initial) }
    HDialog(title, onDismiss = onDismiss, body = {
            InputBox(t, { t = it }, if (numeric) "number" else "value", Modifier.fillMaxWidth(), maxLines = 3,
            kb = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default)
        }) {
            Btn("Cancel", primary = false, onClick = onDismiss)
            Btn("Save") {
            if (numeric && t.isNotBlank() && t.trim().toDoubleOrNull() == null) Ui.say("Not a number") else onSave(t.trim())
        }
        }
}

@Composable
fun SettingsScreen() {
    val p = P
    val meta by Hub.meta.collectAsState()
    val co = rememberCoroutineScope()
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    suspend fun load() { runCatching { data = Api.get("/v1/settings"); err = null }.onFailure { err = it.message } }
    var tab by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { load() }
    val groups = data?.optJSONArray("groups").objs()
    val tabs = groups.map { it.str("tab").ifBlank { "Other" } }.distinct()
    val cur = tab.ifBlank { tabs.firstOrNull().orEmpty() }
    Column(Modifier.fillMaxSize()) {
        TopBar("Agent", (meta.str("name").ifBlank { Prefs.server ?: "Hermes" }) + " · profile " + meta.str("profile").ifBlank { Prefs.profile ?: "default" }) {
            IconBtn(Ic.search, p.tx2) { Ui.push(Route.AllSettingsR) }
            IconBtn(Ic.refresh, p.tx2) { co.launch { load() } }
        }
        if (tabs.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Seg(tabs.map { it to it }, cur) { tab = it }
        }
        LazyColumn(Modifier.weight(1f)) {
            item { RestartBanner() }
            if (data == null) item { if (err != null) ErrorPanel(err!!) { co.launch { load() } } else Skeleton(6) }
            groups.filter { it.str("tab").ifBlank { "Other" } == cur }.forEach { g ->
                item(key = "g" + g.str("title")) {
                    Section(g.str("title"), null)
                    Panel {
                        g.optJSONArray("fields").objs().forEachIndexed { i, f -> if (i > 0) Line(); SettingField(f) }
                    }
                }
            }
            if (data != null) item {
                Spacer(Modifier.height(18.dp))
                Panel { NavRow(Ic.search, "All settings", "Every key in config.yaml, searchable") { Ui.push(Route.AllSettingsR) } }
                Text("Changes go through hermes config set, the same path as the CLI. Secrets stay on the machine.",
                    style = sans(12.5f, 400, p.dim, 18f), modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp))
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
fun AllSettingsScreen() {
    var fields by remember { mutableStateOf<List<JSONObject>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("") }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(retry) {
        err = null
        runCatching { fields = Api.get("/v1/settings/all").optJSONArray("fields").objs() }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; err = it.message ?: "Connection failed" }
    }
    val all = fields.orEmpty()
    val cats = all.groupingBy { it.str("category") }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    val list = all.filter { (cat.isBlank() || it.str("category") == cat) && (q.isBlank() || it.str("key").contains(q.trim(), true) || it.str("help").contains(q.trim(), true)) }
    Column(Modifier.fillMaxSize()) {
        TopBar("All settings", fields?.let { "${it.size} keys" } ?: "Loading…")
        InputBox(q, { q = it }, "Search keys", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), maxLines = 1)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("All", cat.isBlank()) { cat = "" }
            for (c in cats) Chip(c, cat == c) { cat = c }
        }
        LazyColumn(Modifier.weight(1f)) {
            if (err != null) item { ErrorPanel("Could not load settings: $err") { retry++ } }
            else if (fields == null) item { Skeleton() }
            itemsIndexed(list) { i, f -> if (i > 0) Line(); SettingField(f, showKey = true) }
            if (fields != null && list.isEmpty()) item { Empty("Nothing matches", "Try another word or category.") }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

// ---------------------------------------------------------------- skills, plugins, MCP
@Composable
fun ExtScreen() {
    var tab by remember { mutableStateOf("skills") }
    Column(Modifier.fillMaxSize()) {
        TopBar("Skills & plugins", "What ${agentName()} can use") {
            Text("Hub", style = sans(14f, 600, P.acct), modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(P.accd)
                .clickable { Mk.tab.value = tab; Ui.push(Route.HubR) }.padding(horizontal = 12.dp, vertical = 7.dp))
            Spacer(Modifier.width(8.dp))
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Seg(listOf("skills" to "Skills", "plugins" to "Plugins", "mcp" to "MCP"), tab) { tab = it }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                "plugins" -> PluginsTab()
                "mcp" -> McpTab()
                else -> SkillsTab()
            }
        }
    }
}

@Composable
fun SkillsTab() {
    val p = P
    val co = rememberCoroutineScope()
    var skills by remember { mutableStateOf<List<JSONObject>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    val on = remember { mutableStateMapOf<String, Boolean>() }
    var rk by remember { mutableStateOf(0) }
    LaunchedEffect(rk) { runCatching { skills = Api.get("/v1/skills").optJSONArray("skills").objs() }.onFailure { err = it.message } }
    val all = skills.orEmpty()
    fun enabled(s: JSONObject) = on[s.str("name")] ?: s.optBoolean("enabled", true)
    val list = all.filter { s ->
        (when (filter) { "off" -> !enabled(s); "agent" -> s.str("provenance") == "agent"; "hub" -> s.str("provenance") == "hub"; "bundled" -> s.str("provenance") == "bundled"; else -> true }) &&
            (q.isBlank() || s.str("name").contains(q.trim(), true) || s.str("description").contains(q.trim(), true))
    }
    val groups = list.groupBy { it.str("category").ifBlank { "general" } }.toSortedMap()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            InputBox(q, { q = it }, "Search skills", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), maxLines = 1)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("All ${all.size}", filter == "all") { filter = "all" }
                Chip("Off ${all.count { !enabled(it) }}", filter == "off") { filter = "off" }
                Chip("Made by ${agentName()}", filter == "agent") { filter = "agent" }
                Chip("Hub", filter == "hub") { filter = "hub" }
                Chip("Bundled", filter == "bundled") { filter = "bundled" }
            }
            Text("Toggles apply to new chats. Running chats keep their skill list so the prompt cache holds.", style = sans(12.5f, 400, p.dim, 17f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
        }
        if (skills == null) item { if (err != null) ErrorPanel(err!!) { err = null; rk++ } else Skeleton(6) }
        else if (list.isEmpty()) item {
            Empty(if (all.isEmpty()) "No skills installed" else "No matching skills",
                if (all.isEmpty()) "Browse the Hub to add skills." else "Try another word or filter.")
        }
        groups.forEach { (cat, l) ->
            item { Section(cat, l.size.toString()) }
            items(l) { s ->
                val name = s.str("name")
                val en = enabled(s)
                Row(Modifier.fillMaxWidth().clickable { Ui.push(Route.SkillR(name)) }.padding(start = 20.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = sans(15f, 600, if (en) p.tx else p.mu), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            val prov = s.str("provenance")
                            if (prov != "bundled") { Spacer(Modifier.width(6.dp)); Pill(if (prov == "agent") agentName().lowercase() else prov, if (prov == "agent") p.acct else p.tx2, if (prov == "agent") p.accd else p.s3) }
                            if (s.optInt("usage") > 0) { Spacer(Modifier.width(6.dp)); Text("${s.optInt("usage")}×", style = mono(11f, 500, p.dim)) }
                        }
                        Text(s.str("description"), style = sans(13f, 400, p.mu, 18f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (s.optBoolean("locked")) Text(if (s.str("provenance") == "plugin") "plugin" else "always on", style = mono(11.5f, 500, p.mu), modifier = Modifier.padding(end = 8.dp))
                    else Toggle(en, { v ->
                        on[name] = v
                        co.launch {
                            runCatching { Api.post("/v1/skills/toggle", JSONObject().put("name", name).put("enabled", v)) }
                                .onSuccess { r -> on[name] = r.optBoolean("enabled", v); if (r.optBoolean("enabled", v) != v) Ui.say("$name can't be turned off") }
                                .onFailure { on[name] = !v; Ui.say(it.message) }
                        }
                    })
                }
            }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
fun PluginsTab() {
    val p = P
    val co = rememberCoroutineScope()
    val act = LocalAct.current
    var plugins by remember { mutableStateOf<List<JSONObject>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var showAuto by remember { mutableStateOf(false) }
    suspend fun load() { runCatching { plugins = Api.get("/v1/plugins").optJSONArray("plugins").objs(); err = null }.onFailure { err = it.message } }
    LaunchedEffect(Unit) { load() }
    val all = plugins.orEmpty()
    val mine = all.filter { !it.optBoolean("auto") }.sortedWith(compareByDescending<JSONObject> { it.optBoolean("configured") }.thenBy { it.str("name") })
    val auto = all.filter { it.optBoolean("auto") }.sortedWith(compareBy<JSONObject> { it.str("kind") }.thenBy { it.str("key") })
    LazyColumn(Modifier.fillMaxSize()) {
        item { RestartBanner() }
        if (plugins == null) item { if (err != null) ErrorPanel(err!!) { co.launch { load() } } else Skeleton(6) }
        if (mine.isNotEmpty()) item { Section("Plugins", "${mine.count { it.optBoolean("configured") }} on") }
        items(mine) { pl -> PluginRow(pl) { v ->
            co.launch {
                val nm = pl.str("key").ifBlank { pl.str("name") }
                runCatching { Guard.call(act, "plugin", nm, v, (if (v) "Enable " else "Disable ") + nm) { h -> Api.post("/v1/plugins/toggle", JSONObject().put("name", nm).put("enabled", v), h) } }
                    .onSuccess { Ctl.needRestart.value = true; load() }.onFailure { if (it.message != "Cancelled") Ui.say(it.message); load() }
            }
        } }
        if (auto.isNotEmpty()) item {
            Row(Modifier.clickable { showAuto = !showAuto }) { Section("Built in", if (showAuto) "hide" else "show ${auto.size}") }
            if (!showAuto) Text("Backends and platform adapters load on their own. Pick between them in Settings.", style = sans(12.5f, 400, p.dim, 17f),
                modifier = Modifier.padding(horizontal = 20.dp))
        }
        if (showAuto) items(auto) { pl -> PluginRow(pl) {} }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
fun PluginRow(pl: JSONObject, onToggle: (Boolean) -> Unit) {
    val p = P
    var on by remember(pl.toString()) { mutableStateOf(pl.optBoolean("configured")) }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pl.str("key").ifBlank { pl.str("name") }, style = sans(15f, 600, if (on) p.tx else p.mu), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp)); Pill(pl.str("kind"), p.tx2, p.s3)
                if (pl.str("version").isNotBlank()) { Spacer(Modifier.width(6.dp)); Text(pl.str("version"), style = mono(11f, 500, p.dim)) }
            }
            if (pl.str("description").isNotBlank()) Text(pl.str("description"), style = sans(13f, 400, p.mu, 18f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val stats = listOf("tools", "hooks", "commands").mapNotNull { k -> pl.optInt(k).takeIf { it > 0 }?.let { "$it ${if (it == 1) k.dropLast(1) else k}" } }
            val line = (stats + listOfNotNull(if (pl.optBoolean("pending")) "restart to apply" else null)).joinToString(" · ")
            if (line.isNotBlank()) Text(line, style = mono(11.5f, 500, if (pl.optBoolean("pending")) p.att else p.dim))
            if (pl.str("error").isNotBlank() && pl.optBoolean("configured") && !pl.optBoolean("pending")) Text(pl.str("error"), style = mono(11.5f, 500, p.bad), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        if (pl.optBoolean("locked")) Text(if (pl.optBoolean("auto")) (if (pl.optBoolean("enabled")) "loaded" else "off") else "required", style = mono(11.5f, 500, p.mu), modifier = Modifier.padding(end = 8.dp))
        else Toggle(on, { v -> on = v; onToggle(v) })
    }
}

@Composable
fun McpTab() {
    val p = P
    var servers by remember { mutableStateOf<List<JSONObject>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var rk by remember { mutableStateOf(0) }
    LaunchedEffect(rk) { runCatching { val o = Api.get("/v1/mcp"); servers = o.optJSONArray("servers").objs(); err = o.str("error").ifBlank { null } }.onFailure { err = it.message } }
    val l = servers
    LazyColumn(Modifier.fillMaxSize()) {
        if (l == null) item { if (err != null) ErrorPanel(err!!) { err = null; rk++ } else Skeleton() }
        else if (l.isEmpty()) item { Empty("No MCP servers", "Add one on the machine with hermes mcp add.") }
        else {
            item { Section("Servers", l.size.toString()) }
            items(l) { s ->
                val st = s.str("status")
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(when (st) { "connected" -> p.good; "failed" -> p.bad; "disabled" -> p.dim; "connecting" -> p.att; else -> p.mu }, 9.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.str("name"), style = sans(15f, 600, p.tx))
                        Text(listOfNotNull(s.str("transport"), s.optInt("tools").takeIf { it > 0 }?.let { "$it tools" }, st).joinToString(" · "), style = mono(11.5f, 500, p.mu))
                        if (s.str("error").isNotBlank()) Text(s.str("error"), style = mono(11.5f, 500, p.bad), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item { Text("Tool counts show for servers this gateway has connected. Lazy servers start on first use.", style = sans(12.5f, 400, p.dim, 17f),
                modifier = Modifier.padding(20.dp)) }
        }
    }
}

@Composable
fun SkillScreen(name: String) {
    val p = P
    var d by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var rk by remember { mutableStateOf(0) }
    LaunchedEffect(name, rk) { runCatching { d = Api.get("/v1/skill?name=" + Uri.encode(name)) }.onFailure { err = it.message } }
    val o = d
    val raw = o?.str("content").orEmpty()
    val body = if (raw.startsWith("---")) raw.substringAfter("\n---", raw).removePrefix("\n") else raw
    Column(Modifier.fillMaxSize()) {
        TopBar(name, o?.str("path")?.ifBlank { null })
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
            if (o == null) item { if (err != null) ErrorPanel(err!!) { err = null; rk++ } else Skeleton(5) }
            else {
                if (o.str("description").isNotBlank()) item { Text(o.str("description"), style = sans(15f, 400, p.tx2, 22f)); Spacer(Modifier.height(12.dp)) }
                val missing = o.optJSONArray("missing_env").strs() + o.optJSONArray("missing_commands").strs()
                if (missing.isNotEmpty()) item {
                    Text("Needs setup: " + missing.joinToString(", "), style = mono(12f, 500, p.att), modifier = Modifier.padding(bottom = 12.dp))
                }
                val files = o.optJSONArray("files").strs()
                if (files.isNotEmpty()) item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { for (f in files) Chip(f) }
                    Spacer(Modifier.height(14.dp))
                }
                item { Md(body.take(60000)) }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

// ---------------------------------------------------------------- memory
@Composable
fun MemoryScreen() {
    val p = P
    var d by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val an = agentName()
    LaunchedEffect(tick) { runCatching { d = Api.get("/v1/memory") }.onFailure { err = it.message } }
    editing?.let { (t, e) -> MemoryEditor(t, e) { editing = null; tick++ } }
    Column(Modifier.fillMaxSize()) {
        TopBar("Memory", "Loaded into every new session")
        LazyColumn(Modifier.weight(1f)) {
            val o = d
            if (o == null) item { if (err != null) ErrorPanel(err!!) { err = null; tick++ } else Skeleton(6) }
            else for ((k, title) in listOf("user" to "About you", "memory" to "$an's notes")) {
                val m = o.optJSONObject(k) ?: continue
                item(key = k) {
                    val used = m.optInt("chars"); val lim = m.optInt("limit")
                    Section(title, if (lim > 0) "$used / $lim" else "$used chars", if (lim > 0 && used > lim * 0.9) p.att else p.mu)
                    if (lim > 0) Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.s3)) {
                        Box(Modifier.fillMaxWidth((used.toFloat() / lim).coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (used > lim * 0.9) p.att else p.acct))
                    }
                    if (!m.optBoolean("enabled", true)) Text("Turned off in settings", style = sans(13f, 500, p.att), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                    Spacer(Modifier.height(8.dp))
                    Panel {
                        val es = m.optJSONArray("entries").strs()
                        if (es.isEmpty()) Text("Empty", style = sans(14f, 400, p.mu), modifier = Modifier.padding(16.dp))
                        es.forEachIndexed { i, e ->
                            if (i > 0) Line()
                            Text(e, style = sans(14f, 400, p.tx, 20f), modifier = Modifier.fillMaxWidth().clickable { editing = k to e }.padding(horizontal = 16.dp, vertical = 11.dp))
                        }
                        Line()
                        Row(Modifier.fillMaxWidth().clickable { editing = k to null }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icn(Ic.plus, p.acct, 16.dp); Spacer(Modifier.width(8.dp)); Text("Add", style = sans(14.5f, 600, p.acct))
                        }
                    }
                }
            }
            item {
                Text("Tap an entry to edit or forget it. Edits pass the same size limits and safety scan as ${agentName()}'s own writes.", style = sans(12.5f, 400, p.dim, 18f),
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp))
            }
        }
    }
}
