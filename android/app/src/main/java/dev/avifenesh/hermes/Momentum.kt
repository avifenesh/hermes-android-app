package dev.avifenesh.hermes

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Momentum: the agent system's own report (momentum report v1), shown above the scout on the radar page.
 * Read-only and plain text. Nothing here notifies; the only signal outside the page is the needs-you count on
 * the Radar row in Me.
 */
object Momentum {
    /** Display order: what only you can decide, what broke, what runs, what shipped, what was decided for you. */
    val SECTIONS = listOf("needs_you" to "Needs you", "problems" to "Problems", "running" to "Running",
        "shipped" to "Shipped", "decided" to "Decided")
    const val SHOWN = 5

    /** The last /v1/radar/momentum answer, null until one arrives. */
    val state = MutableStateFlow<JSONObject?>(null)
    /** Item ids already read, per section. A section holding an id outside its set opens; a read one shows its count. */
    val read = MutableStateFlow(parseRead(Prefs.momentumRead))

    suspend fun load(force: Boolean = false) {
        val ticket = Api.epoch.capture()
        val o = try { Api.get("/v1/radar/momentum" + if (force) "?refresh=1" else "") }
            catch (e: ApiError) { if (e.code == 404) JSONObject().put("available", false) else throw e }  // older plugin
        Api.epoch.commit(ticket) { state.value = o }
    }

    fun reset() { state.value = null; read.value = emptyMap(); Prefs.momentumRead = null }

    /** The report, when this machine has momentum and has produced one good copy. */
    fun report(o: JSONObject?): JSONObject? = o?.takeIf { it.optBoolean("available") }?.optJSONObject("report")
    fun items(rep: JSONObject?, key: String): List<JSONObject> = rep?.optJSONArray(key).objs()
    /** Everything in the section; the list stops at 20, the count does not. */
    fun count(rep: JSONObject?, key: String): Int = maxOf(rep?.optJSONObject("counts")?.optInt(key, 0) ?: 0, items(rep, key).size)
    fun unread(rep: JSONObject?, key: String, seen: Map<String, Set<String>> = read.value): Set<String> {
        val known = seen[key].orEmpty()
        return items(rep, key).map { it.str("id") }.filter { it.isNotBlank() && it !in known }.toSet()
    }

    /** Remember the listed ids of these sections, so each shows only its count until something new lands. */
    fun markRead(rep: JSONObject?, keys: Collection<String>) {
        if (rep == null || keys.isEmpty()) return
        val next = read.value.toMutableMap()
        for (k in keys) next[k] = items(rep, k).map { it.str("id") }.filter { it.isNotBlank() }.toSet()
        if (next == read.value) return
        read.value = next
        Prefs.momentumRead = JSONObject(next.mapValues { JSONArray(it.value.toList()) }).toString()
    }

    private fun parseRead(s: String?): Map<String, Set<String>> {
        if (s.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(s)
            o.keys().asSequence().associateWith { k -> o.optJSONArray(k).strs().toSet() }
        }.getOrDefault(emptyMap())
    }
}

/** Right side of the Momentum heading: when the copy was made, and whether it is stale. */
fun momentumWhen(o: JSONObject): String? {
    val at = o.optDouble("fetched_at", 0.0)
    if (at <= 0) return null
    return if (o.optBoolean("stale")) "stale · " + clock(at) else clock(at)
}

/**
 * The five sections in one panel. [open] holds this visit's open state; a section without an entry opens when
 * it has unread items. Sections seen open on screen are added to [seen], so leaving the page marks them read.
 */
@Composable
fun MomentumPanel(o: JSONObject, open: Map<String, Boolean>, seen: MutableSet<String>, onToggle: (String, Boolean) -> Unit) {
    val p = P
    val rep = Momentum.report(o)
    val readIds by Momentum.read.collectAsState()
    Panel(Modifier.padding(bottom = 4.dp)) {
        if (rep == null) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("No report yet", style = sans(14.5f, 600, p.tx2))
                Text(o.str("error").ifBlank { "momentum has not produced a report" }, style = mono(11.5f, 500, p.mu),
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            return@Panel
        }
        Momentum.SECTIONS.forEachIndexed { i, (key, label) ->
            if (i > 0) Line()
            val fresh = Momentum.unread(rep, key, readIds)
            // A dot marks what is new since the last read; on a first look everything is, so nothing is marked.
            val marked = if (readIds[key].isNullOrEmpty()) emptySet() else fresh
            MomentumSection(rep, key, label, open[key] ?: fresh.isNotEmpty(), marked, seen) { onToggle(key, it) }
        }
        if (o.optBoolean("stale")) {
            Line()
            Text("Couldn't refresh: " + o.str("error").ifBlank { "no reason given" }, style = mono(11.5f, 500, p.mu),
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }
}

@Composable
private fun MomentumSection(rep: JSONObject, key: String, label: String, isOpen: Boolean, marked: Set<String>,
                            seen: MutableSet<String>, onToggle: (Boolean) -> Unit) {
    val p = P
    val all = Momentum.items(rep, key)
    val n = Momentum.count(rep, key)
    val tone = when (key) { "needs_you" -> p.att; "problems" -> p.bad; "running" -> p.acct; "shipped" -> p.good; else -> p.tx2 }
    val canOpen = all.isNotEmpty()
    val showing = isOpen && canOpen
    Column(Modifier.fillMaxWidth().onGloballyPositioned { c ->
        // Read means it was on screen while open: most of it, or a screenful of a tall one.
        val visible = c.boundsInWindow().height
        if (showing && (visible >= c.size.height * 0.5f || visible >= 600f)) seen += key
    }) {
        Row(Modifier.fillMaxWidth().clickable(enabled = canOpen, role = Role.Button, onClickLabel = if (showing) "Show only the count" else "Show items") { onToggle(!showing) }
            .heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (key == "running" && n > 0) LiveDot(8.dp, tone) else Dot(if (n > 0) tone else p.dim, 8.dp)
            Spacer(Modifier.width(12.dp))
            Text(label, style = sans(15f, 600, if (n > 0) p.tx else p.mu), modifier = Modifier.weight(1f))
            Text(if (n > 999) "999+" else n.toString(), style = mono(13.5f, 600, if (n > 0) p.tx2 else p.dim))
            Spacer(Modifier.width(8.dp))
            if (canOpen) Icn(if (showing) Ic.chevU else Ic.chevD, p.mu, 16.dp) else Spacer(Modifier.width(16.dp))
        }
        if (showing) {
            all.take(Momentum.SHOWN).forEach { MomentumItem(it, key, it.str("id") in marked) }
            val more = n - minOf(all.size, Momentum.SHOWN)
            if (more > 0) Text("and $more more", style = mono(11.5f, 500, p.mu), modifier = Modifier.padding(start = 36.dp, end = 16.dp, bottom = 14.dp))
        }
    }
}

@Composable
private fun MomentumItem(o: JSONObject, key: String, fresh: Boolean) {
    val p = P
    val id = o.str("id")
    var full by rememberSaveable(key, id) { mutableStateOf(false) }
    val effect = o.str("effect")
    val detail = o.str("detail")
    // Shipped leads with its measured effect; everything else with its detail. Tapping shows both in full.
    val lead = if (key == "shipped") effect.ifBlank { detail } else detail.ifBlank { effect }
    val rest = if (lead == effect) detail else effect
    val ts = parseTime(o.opt("at"))
    val links = o.optJSONArray("links").objs().filter { it.str("url").let { u -> u.startsWith("https://", true) || u.startsWith("http://", true) } }
    @Composable
    fun line(text: String) {
        if (text.isBlank()) return
        val isEffect = text == effect
        Text(text, style = if (isEffect) mono(12f, 500, p.good) else sans(13f, 400, p.tx2, 18f), maxLines = if (full) 8 else 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
    Row(Modifier.fillMaxWidth().clickable(enabled = lead.isNotBlank() || rest.isNotBlank(), onClickLabel = if (full) "Show less" else "Show all") { full = !full }
        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = if (links.isEmpty()) 10.dp else 2.dp)) {
        Box(Modifier.width(20.dp)) {
            // Not read yet: a small dot under the section's own.
            if (fresh) Box(Modifier.padding(start = 1.dp, top = 7.dp).size(6.dp).clip(CircleShape).background(p.acct))
        }
        Column(Modifier.weight(1f)) {
            Row {
                Text(o.str("title"), style = sans(14.5f, 500, p.tx, 20f), maxLines = if (full) 4 else 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                if (ts != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(rel(ts), style = mono(11.5f, 500, p.mu), modifier = Modifier.padding(top = 2.dp))
                }
            }
            line(lead)
            if (full) line(rest)
            if (links.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                links.forEach { LinkChip(it.str("label"), it.str("url")) }
            }
        }
    }
}

/** A link from the report: opens in the browser. The touch area is taller than the pill it shows. */
@Composable
private fun LinkChip(label: String, url: String) {
    val p = P
    val ctx = LocalContext.current
    Box(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Open in browser") {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Ui.say("No app can open this link") }
    }.padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
        Text(label.ifBlank { url.toUri().host ?: url }, style = mono(11.5f, 600, p.acct), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(p.accd).padding(horizontal = 8.dp, vertical = 4.dp))
    }
}
