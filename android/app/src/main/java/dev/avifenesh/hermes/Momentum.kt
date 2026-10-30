package dev.avifenesh.hermes

import android.content.Intent
import android.os.SystemClock
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LayoutBoundsHolder
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
    /** Why this phone's last refresh failed (the machine was unreachable, say), or null when it worked. */
    val failed = MutableStateFlow<String?>(null)
    /** Rows already read, per section (see [rowKey]). A section drawing a row outside its set opens; a read one shows its count. */
    val read = MutableStateFlow(parseRead(Prefs.momentumRead))

    suspend fun load(force: Boolean = false) {
        val ticket = Api.epoch.capture()
        fun fail(why: String?) { runCatching { Api.epoch.commit(ticket) { failed.value = why ?: "unreachable" } } }
        val o = try { Api.get("/v1/radar/momentum" + if (force) "?refresh=1" else "") }
            catch (e: ApiError) { if (e.code == 404) JSONObject().put("available", false) else { fail(e.message); throw e } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e.message); throw e }
        Api.epoch.commit(ticket) {
            state.value = o
            failed.value = null
            report(o)?.let { prune(it) }
        }
    }

    fun reset() { state.value = null; failed.value = null; read.value = emptyMap(); Prefs.momentumRead = null }

    /** After a machine switch restored that machine's saved read state into Prefs. */
    fun reloadRead() { read.value = parseRead(Prefs.momentumRead) }

    /** The report, when this machine has momentum and has produced one good copy. */
    fun report(o: JSONObject?): JSONObject? = o?.takeIf { it.optBoolean("available") }?.optJSONObject("report")
    fun items(rep: JSONObject?, key: String): List<JSONObject> = rep?.optJSONArray(key).objs()
    /** Everything in the section; the list stops at 20, the count does not. */
    fun count(rep: JSONObject?, key: String): Int = maxOf(rep?.optJSONObject("counts")?.optInt(key, 0) ?: 0, items(rep, key).size)

    /**
     * What a row is, for reading. momentum re-adds a note under the same id with a new time when it stands again
     * (a problem that came back, a question asked again), so a read is of the id at that time. A running item's
     * time moves while it runs, so there the id alone is the row.
     */
    fun rowKey(section: String, item: JSONObject): String =
        if (section == "running") item.str("id") else item.str("id") + "\u001f" + item.str("at")
    private fun keys(section: String, list: List<JSONObject>): Set<String> =
        list.filter { it.str("id").isNotBlank() }.map { rowKey(section, it) }.toSet()
    /** The rows the section draws: the first [SHOWN]. Only these can be read, so only these can be unread. */
    fun shown(rep: JSONObject?, key: String): Set<String> = keys(key, items(rep, key).take(SHOWN))
    fun unread(rep: JSONObject?, key: String, seen: Map<String, Set<String>> = read.value): Set<String> =
        shown(rep, key) - seen[key].orEmpty()

    /** Record rows read on screen; earlier reads still listed in [rep] (the newest copy) stay read. */
    fun markRead(drawn: Map<String, Set<String>>, rep: JSONObject?) {
        if (drawn.isEmpty()) return
        val now = read.value
        save(now + drawn.mapValues { (k, d) -> (now[k].orEmpty() intersect keys(k, items(rep, k))) + d })
    }

    /**
     * Forget reads of rows a good copy no longer lists, in every section, so a row that comes back is new. A section
     * emptied this way keeps its (empty) entry: it has been read before, so what lands next gets a dot.
     */
    private fun prune(rep: JSONObject) {
        val now = read.value
        save(now.mapValues { (k, r) -> r intersect keys(k, items(rep, k)) })
    }

    private fun save(next: Map<String, Set<String>>) {
        if (next == read.value) return
        read.value = next
        Prefs.momentumRead = JSONObject(next.mapValues { JSONArray(it.value.toList()) }).toString()
    }

    fun parseRead(s: String?): Map<String, Set<String>> {
        if (s.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(s)
            o.keys().asSequence().associateWith { k -> o.optJSONArray(k).strs().toSet() }
        }.getOrDefault(emptyMap())
    }
}

/**
 * What the radar page has shown during one visit, row by row. A row counts as read once it was at least half on
 * screen for one continuous second while the app was in front (the usual "viewed" bar), so flinging past it, or a
 * glance before switching apps, reads nothing. Time in the background does not count, and a row still on screen
 * when the app comes back starts counting again.
 */
class MomentumSightings(restored: Map<String, Set<String>> = emptyMap()) {
    private val seen = restored.mapValues { it.value.toMutableSet() }.toMutableMap()
    private val visible = mutableSetOf<Pair<String, String>>()
    private val since = mutableMapOf<Pair<String, String>, Long>()
    private var started = true

    fun row(section: String, key: String, onScreen: Boolean, now: Long = SystemClock.uptimeMillis()) {
        val r = section to key
        if (onScreen) {
            visible += r
            if (started) since.putIfAbsent(r, now)
        } else {
            visible -= r
            settle(r, now)
        }
    }

    private fun settle(r: Pair<String, String>, now: Long) {
        val start = since.remove(r) ?: return
        if (now - start >= DWELL_MS) seen.getOrPut(r.first) { mutableSetOf() } += r.second
    }

    /** The app went to the background (or is being recreated): settle what was on screen, stop the clock. */
    fun stop(now: Long = SystemClock.uptimeMillis()) {
        for (r in since.keys.toList()) settle(r, now)
        started = false
    }

    /** The app is in front again: rows still on screen count from now. */
    fun start(now: Long = SystemClock.uptimeMillis()) {
        started = true
        for (r in visible) since.putIfAbsent(r, now)
    }

    fun snapshot(): Map<String, Set<String>> = seen.mapValues { it.value.toSet() }

    /** Hand over what was read so far, once; rows still on screen keep counting. */
    fun drain(now: Long = SystemClock.uptimeMillis()): Map<String, Set<String>> {
        for (r in since.keys.toList()) settle(r, now)
        if (started) for (r in visible) since.putIfAbsent(r, now)
        return snapshot().also { seen.clear() }
    }

    companion object {
        const val DWELL_MS = 1000L
        val Saver: Saver<MomentumSightings, String> = Saver(
            save = { s -> JSONObject(s.snapshot().mapValues { JSONArray(it.value.toList()) }).toString() },
            restore = { MomentumSightings(Momentum.parseRead(it)) })
    }
}

/** This visit's open state per section, kept across a rotation. */
val MomentumOpenSaver: Saver<SnapshotStateMap<String, Boolean>, Any> = listSaver(
    save = { m -> m.map { (k, v) -> "$k=${if (v) 1 else 0}" } },
    restore = { l -> mutableStateMapOf<String, Boolean>().apply { l.forEach { put(it.substringBeforeLast('='), it.endsWith("=1")) } } })

/** When the copy was made: the time today, the day and time this week, the date and time before that. */
fun momentumStamp(ts: Double): String {
    val then = Calendar.getInstance().apply { timeInMillis = (ts * 1000).toLong() }
    val now = Calendar.getInstance()
    val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    val pattern = when {
        sameDay -> "HH:mm"
        nowS() - ts < 6 * 86400 -> "EEE HH:mm"
        else -> "d MMM HH:mm"
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(then.timeInMillis))
}

/** Right side of the Momentum heading, and whether it reads as stale (the machine's refresh or this phone's failed). */
fun momentumWhen(o: JSONObject, phoneFailed: Boolean): Pair<String?, Boolean> {
    val at = o.optDouble("fetched_at", 0.0)
    val stale = o.optBoolean("stale") || phoneFailed
    if (at <= 0) return (if (phoneFailed) "stale" else null) to stale
    return (if (stale) "stale · " + momentumStamp(at) else momentumStamp(at)) to stale
}

/**
 * The five sections in one panel. [open] holds this visit's open state; a section without an entry opens when
 * it draws an unread row. Rows report their time on screen, measured against [viewport], to [sightings].
 */
@Composable
fun MomentumPanel(o: JSONObject, open: Map<String, Boolean>, sightings: MomentumSightings, viewport: LayoutBoundsHolder,
                  onToggle: (String, Boolean) -> Unit) {
    val p = P
    val rep = Momentum.report(o)
    val readIds by Momentum.read.collectAsState()
    val phoneFailed by Momentum.failed.collectAsState()
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
            val marked = if (key in readIds) fresh else emptySet()
            MomentumSection(rep, key, label, open[key] ?: fresh.isNotEmpty(), marked, sightings, viewport) { onToggle(key, it) }
        }
        val why = phoneFailed ?: o.str("error").takeIf { o.optBoolean("stale") }
        if (why != null) {
            Line()
            Text("Couldn't refresh: " + why.ifBlank { "no reason given" }, style = mono(11.5f, 500, p.mu),
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }
}

@Composable
private fun MomentumSection(rep: JSONObject, key: String, label: String, isOpen: Boolean, marked: Set<String>,
                            sightings: MomentumSightings, viewport: LayoutBoundsHolder, onToggle: (Boolean) -> Unit) {
    val p = P
    val all = Momentum.items(rep, key)
    val n = Momentum.count(rep, key)
    val tone = when (key) { "needs_you" -> p.att; "problems" -> p.bad; "running" -> p.acct; "shipped" -> p.good; else -> p.tx2 }
    val canOpen = all.isNotEmpty()
    val showing = isOpen && canOpen
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()
            .then(if (canOpen) Modifier.clickable(role = Role.Button, onClickLabel = if (showing) "Show only the count" else "Show items") { onToggle(!showing) }
                .semantics { stateDescription = if (showing) "Expanded" else "Collapsed" }
            else Modifier.semantics(mergeDescendants = true) {})
            .heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (key == "running" && n > 0) LiveDot(8.dp, tone) else Dot(if (n > 0) tone else p.dim, 8.dp)
            Spacer(Modifier.width(12.dp))
            Text(label, style = sans(15f, 600, if (n > 0) p.tx else p.mu), modifier = Modifier.weight(1f))
            Text(if (n > 999) "999+" else n.toString(), style = mono(13.5f, 600, if (n > 0) p.tx2 else p.dim))
            Spacer(Modifier.width(8.dp))
            if (canOpen) Icn(if (showing) Ic.chevU else Ic.chevD, p.mu, 16.dp) else Spacer(Modifier.width(16.dp))
        }
        if (showing) {
            all.take(Momentum.SHOWN).forEach {
                val row = Momentum.rowKey(key, it)
                key(row) { MomentumItem(it, key, row, row in marked, sightings, viewport) }
            }
            val more = n - minOf(all.size, Momentum.SHOWN)
            if (more > 0) Text("and $more more", style = mono(11.5f, 500, p.mu), modifier = Modifier.padding(start = 36.dp, end = 16.dp, bottom = 14.dp))
        }
    }
}

@Composable
private fun MomentumItem(o: JSONObject, section: String, row: String, fresh: Boolean, sightings: MomentumSightings,
                         viewport: LayoutBoundsHolder) {
    val p = P
    var full by rememberSaveable(row) { mutableStateOf(false) }
    val effect = o.str("effect")
    val detail = o.str("detail")
    // Shipped leads with its measured effect; everything else with its detail. Tapping shows both in full.
    val lead = if (section == "shipped") effect.ifBlank { detail } else detail.ifBlank { effect }
    val rest = if (lead == effect) detail else effect
    val ts = parseTime(o.opt("at"))
    val links = o.optJSONArray("links").objs().filter { it.str("url").let { u -> u.startsWith("https://", true) || u.startsWith("http://", true) } }
    // A row that leaves the composition (its section closed, the panel scrolled out) has left the screen.
    DisposableEffect(row) { onDispose { sightings.row(section, row, false) } }
    @Composable
    fun line(text: String) {
        if (text.isBlank()) return
        val isEffect = text == effect
        Text(text, style = if (isEffect) mono(12f, 500, p.good) else sans(13f, 400, p.tx2, 18f), maxLines = if (full) 8 else 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
    val expandable = lead.isNotBlank() || rest.isNotBlank()
    Row(Modifier.fillMaxWidth()
        .onVisibilityChanged(minFractionVisible = 0.5f, viewportBounds = viewport) { sightings.row(section, row, it) }
        .then(if (expandable) Modifier.clickable(onClickLabel = if (full) "Show less" else "Show all") { full = !full }
            else Modifier.semantics(mergeDescendants = true) {})
        .semantics { if (fresh) stateDescription = "New" }
        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = if (links.isEmpty()) 10.dp else 0.dp)) {
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

/** A link from the report: opens in the browser. A 48dp touch target around the app's own pill. */
@Composable
private fun LinkChip(label: String, url: String) {
    val p = P
    val ctx = LocalContext.current
    Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Open in browser") {
        // Intent filters match schemes case-sensitively: "HTTPS://" would find no browser.
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri().normalizeScheme()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Ui.say("No app can open this link") }
    }.padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
        Pill(label.ifBlank { url.toUri().host ?: url }, p.acct, p.accd)
    }
}
