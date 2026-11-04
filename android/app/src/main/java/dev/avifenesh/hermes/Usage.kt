package dev.avifenesh.hermes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

// ---------------------------------------------------------------- formatting
fun tok(n: Long): String = Fmt.compact(n)

/** A 0..1 rate as a whole percentage, truncated like the meters always were ("90%" for 0.906). */
private fun meterPct(f: Double): String = Fmt.percent((f * 100).toInt() / 100.0)

/** "claude-opus-5-5" -> "opus-5.5", "anthropic/claude-sonnet-4-20250514" -> "sonnet-4". */
fun shortModel(m: String): String {
    if (m.isBlank()) return ""
    // "global.anthropic.claude-fable-5-1" -> "fable 5.1"; "openai.gpt-5.6-sol" -> "gpt-5.6-sol"
    var s = m.substringAfterLast('/').substringAfterLast('.', m.substringAfterLast('/'))
    if (s.firstOrNull()?.isDigit() == true || s.length < 4) s = m.substringAfterLast('/')   // version-only tail: keep the whole id
    s = s.removePrefix("claude-").replace(Regex("-\\d{8}$"), "")
    s = s.replace(Regex("(\\d+)-(\\d+)$"), "$1.$2").replace(Regex("^([a-z]+)-(\\d)"), "$1 $2")
    return s
}

/** Provider/region segments in front of the part [shortModel] keeps: "global.anthropic.claude-x" -> [global, anthropic]. */
private fun modelPrefix(m: String): List<String> {
    val rest = m.substringAfterLast('/')
    val dot = rest.substringAfterLast('.', rest)
    val tail = if (dot.firstOrNull()?.isDigit() == true || dot.length < 4) rest else dot
    return (m.substringBeforeLast('/', "").split('/') + rest.removeSuffix(tail).split('.')).filter { it.isNotBlank() }
}

/**
 * A label per model id that is unique within [ids]: the [shortModel] label when no other id shares it, else the
 * shortest qualifier that tells them apart ("opus 5.5 (global)", "opus 5.5 (us)", "opus 5.5" for the bare id),
 * else the full id (dated variants).
 */
fun labelsFor(ids: Collection<String>): Map<String, String> {
    val all = ids.filter { it.isNotBlank() }.distinct()
    val out = LinkedHashMap<String, String>()
    for ((short, group) in all.groupBy(::shortModel)) {
        if (group.size == 1) { out[group[0]] = short; continue }
        val pre = group.associateWith(::modelPrefix)
        val shared = pre.values.filter { it.isNotEmpty() }.map { it.toSet() }.reduceOrNull { a, b -> a intersect b }.orEmpty()
        fun qualify(q: (List<String>) -> List<String>) = group.associateWith { id ->
            q(pre.getValue(id)).joinToString(".").let { if (it.isEmpty()) short else "$short ($it)" }
        }
        // Fewest collisions wins, shortest form first; whatever still collides shows its full id.
        val tries = listOf(qualify { it.filter { s -> s !in shared } }, qualify { it.take(1) }, qualify { it })
        val best = tries.minBy { t -> t.values.groupingBy { it }.eachCount().values.filter { it > 1 }.sum() }
        val counts = best.values.groupingBy { it }.eachCount()
        for (id in group) out[id] = best.getValue(id).takeIf { counts.getValue(it) == 1 } ?: id
    }
    val taken = out.values.groupingBy { it }.eachCount()
    return out.mapValues { (id, label) -> if (taken.getValue(label) > 1) id else label }
}

/** [id]'s label among the machine's known models (/v1/me `models`): short unless another known id reads the same. */
fun modelLabel(id: String, known: Collection<String>): String = if (id.isBlank()) "" else labelsFor(known + id).getValue(id)

/** [modelLabel] against the active machine's recent models; recomputed when /v1/me changes (machine switch). */
@Composable
fun modelLabeler(): (String) -> String {
    val known = Hub.meta.collectAsState().value.optJSONArray("models").strs()
    return remember(known) { { id: String -> modelLabel(id, known) } }
}

private fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
private fun JSONObject.lng(k: String): Long = if (has(k) && !isNull(k)) optLong(k, 0) else 0

fun costStr(u: JSONObject): String? = when {
    u.str("cost_status") == "included" -> str(R.string.meter_cost_included)
    else -> u.dbl("cost")?.let { money(it) }
}

/** One-line digest: "opus-5.5 · 92.5k in · 1.0k out · 90% cache · 53 tok/s · $0.12". */
fun usageLine(u: JSONObject, withModel: Boolean = true, label: (String) -> String = ::shortModel): String = buildList {
    if (withModel) label(u.str("model")).takeIf { it.isNotBlank() }?.let { add(it) }
    costStr(u)?.let { add(it) }
    val prompt = u.lng("prompt_tokens").takeIf { it > 0 } ?: (u.lng("input_tokens") + u.lng("cache_read_tokens") + u.lng("cache_write_tokens"))
    if (prompt > 0) add(str(R.string.meter_tokens_in, tok(prompt)))
    if (u.lng("output_tokens") > 0) add(str(R.string.meter_tokens_out, tok(u.lng("output_tokens"))))
    u.dbl("cache_hit")?.let { add(str(R.string.meter_cache_pct, meterPct(it))) }
    u.dbl("tok_s")?.let { add(str(R.string.meter_tok_per_s, it.toInt())) }
}.joinToString(" · ")

// ---------------------------------------------------------------- per-reply footer
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsageFooter(u: JSONObject, ts: Double) {
    val p = P
    var open by remember { mutableStateOf(false) }
    val label = modelLabeler()
    Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 2.dp)) {
        // Collapsed: time and cost only. Tokens, cache and speed live behind the tap.
        Text(if (open) clock(ts) + " · " + usageLine(u, label = label) else listOfNotNull(clock(ts), costStr(u), label(u.str("model")).takeIf { it.isNotBlank() }).joinToString(" · "),
            style = mono(10.5f, 500, p.dim), maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis)
        if (open) {
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                label(u.str("model")).takeIf { it.isNotBlank() }?.let { Chip(it) }
                Chip(stringResource(R.string.meter_chip_in, tok(u.lng("input_tokens"))))
                Chip(stringResource(R.string.meter_chip_cache_read, tok(u.lng("cache_read_tokens"))))
                if (u.lng("cache_write_tokens") > 0) Chip(stringResource(R.string.meter_chip_cache_write, tok(u.lng("cache_write_tokens"))))
                Chip(stringResource(R.string.meter_chip_out, tok(u.lng("output_tokens"))))
                if (u.lng("reasoning_tokens") > 0) Chip(stringResource(R.string.meter_chip_reasoning, tok(u.lng("reasoning_tokens"))))
                u.dbl("tok_s")?.let { Chip(stringResource(R.string.meter_tok_per_s, it.toInt())) }
                val calls = u.lng("calls")
                Chip(if (u.lng("sub_calls") > 0) pluralStringResource(R.plurals.meter_calls_sub, calls.toInt(), calls, u.lng("sub_calls"))
                    else pluralStringResource(R.plurals.meter_calls, calls.toInt(), calls))
                Chip(costStr(u) ?: stringResource(R.string.meter_cost_na))
            }
            ContextBar(u, Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun Chip(text: String) {
    Text(text, style = mono(11.5f, 500, P.tx2), modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(P.s2)
        .border(1.dp, P.line, RoundedCornerShape(9.dp)).padding(horizontal = 9.dp, vertical = 5.dp))
}

@Composable
fun ContextBar(u: JSONObject, modifier: Modifier = Modifier) {
    val p = P
    val max = u.lng("context_max")
    val used = u.lng("context_used")
    if (max <= 0 || used <= 0) return
    val f = (used.toFloat() / max).coerceIn(0f, 1f)
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.meter_context, Fmt.percent((f * 100).toInt() / 100.0), tok(max)), style = mono(11f, 500, p.mu))
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(p.s3)) {
            Box(Modifier.fillMaxWidth(f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(if (f > 0.85f) p.bad else if (f > 0.6f) p.att else p.acct))
        }
    }
}

// ---------------------------------------------------------------- Run screen block
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunUsage(turn: JSONObject?, sess: JSONObject?, host: String) {
    val p = P
    val model = modelLabeler()(turn?.str("model")?.ifBlank { null } ?: sess?.str("model") ?: "")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (model.isNotBlank()) Chip(model)
        sess?.let { s -> Chip(stringResource(R.string.meter_tokens, tok(s.lng("prompt_tokens") + s.lng("output_tokens")))) }
        sess?.let { s -> costStr(s)?.let { Chip(it) } }
        if (host.isNotBlank()) Chip(host)
    }
    if (turn != null) {
        ContextBar(turn, Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.meter_this_turn), style = mono(10.5f, 600, p.mu))
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(stringResource(R.string.meter_stat_in), tok(turn.lng("prompt_tokens")), Modifier.weight(1f))
            Stat(stringResource(R.string.meter_stat_out), tok(turn.lng("output_tokens")), Modifier.weight(1f))
            Stat(stringResource(R.string.meter_stat_cache_hit), turn.dbl("cache_hit")?.let { meterPct(it) } ?: "–", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(stringResource(R.string.meter_stat_tok_s), turn.dbl("tok_s")?.let { "${it.toInt()}" } ?: "–", Modifier.weight(1f))
            Stat(stringResource(R.string.meter_stat_calls), turn.lng("calls").toString(), Modifier.weight(1f))
            Stat(stringResource(R.string.meter_stat_cost), costStr(turn) ?: stringResource(R.string.meter_na), Modifier.weight(1f))
        }
    }
    if (sess != null) {
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.meter_session), style = mono(10.5f, 600, p.mu))
        Spacer(Modifier.height(4.dp))
        val calls = sess.lng("calls")
        Text(listOfNotNull(
            pluralStringResource(R.plurals.meter_session_calls, calls.toInt(), calls),
            stringResource(R.string.meter_tokens_in, tok(sess.lng("prompt_tokens"))),
            stringResource(R.string.meter_tokens_out, tok(sess.lng("output_tokens"))),
            sess.dbl("cache_hit")?.let { stringResource(R.string.meter_cache_pct, meterPct(it)) },
            costStr(sess) ?: stringResource(R.string.meter_cost_na),
        ).joinToString(" · "), style = mono(12f, 500, p.tx2))
    }
}

/** Chat header subtitle per the design: "opus-5.5 · 48.2k tok · $0.61". */
fun headerLine(sess: JSONObject?, turn: JSONObject?, label: (String) -> String = ::shortModel): String? {
    val s = sess ?: return turn?.let { usageLine(it, label = label) }?.ifBlank { null }
    return buildList {
        label(turn?.str("model")?.ifBlank { null } ?: s.str("model")).takeIf { it.isNotBlank() }?.let { add(it) }
        val t = s.lng("prompt_tokens") + s.lng("output_tokens")
        if (t > 0) add(str(R.string.meter_tokens, tok(t)))
        costStr(s)?.let { add(it) }
    }.joinToString(" · ").ifBlank { null }
}
