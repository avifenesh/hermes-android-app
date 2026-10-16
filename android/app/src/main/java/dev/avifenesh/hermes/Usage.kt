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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

// ---------------------------------------------------------------- formatting
fun tok(n: Long): String = when {
    n < 1000 -> "$n"
    n < 100_000 -> "%.1fk".format(n / 1000.0)
    n < 1_000_000 -> "${n / 1000}k"
    else -> "%.1fM".format(n / 1_000_000.0)
}

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

private fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
private fun JSONObject.lng(k: String): Long = if (has(k) && !isNull(k)) optLong(k, 0) else 0

fun costStr(u: JSONObject): String? = when {
    u.str("cost_status") == "included" -> "included"
    else -> u.dbl("cost")?.let { money(it) }
}

/** One-line digest: "opus-5.5 · 92.5k in · 1.0k out · 90% cache · 53 tok/s · $0.12". */
fun usageLine(u: JSONObject, withModel: Boolean = true): String = buildList {
    if (withModel) shortModel(u.str("model")).takeIf { it.isNotBlank() }?.let { add(it) }
    costStr(u)?.let { add(it) }
    val prompt = u.lng("prompt_tokens").takeIf { it > 0 } ?: (u.lng("input_tokens") + u.lng("cache_read_tokens") + u.lng("cache_write_tokens"))
    if (prompt > 0) add("${tok(prompt)} in")
    if (u.lng("output_tokens") > 0) add("${tok(u.lng("output_tokens"))} out")
    u.dbl("cache_hit")?.let { add("${(it * 100).toInt()}% cache") }
    u.dbl("tok_s")?.let { add("${it.toInt()} tok/s") }
}.joinToString(" · ")

// ---------------------------------------------------------------- per-reply footer
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsageFooter(u: JSONObject, ts: Double) {
    val p = P
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 2.dp)) {
        // Collapsed: time and cost only. Tokens, cache and speed live behind the tap.
        Text(if (open) clock(ts) + " · " + usageLine(u) else listOfNotNull(clock(ts), costStr(u), shortModel(u.str("model")).takeIf { it.isNotBlank() }).joinToString(" · "),
            style = mono(10.5f, 500, p.dim), maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis)
        if (open) {
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                shortModel(u.str("model")).takeIf { it.isNotBlank() }?.let { Chip(it) }
                Chip("in ${tok(u.lng("input_tokens"))}")
                Chip("cache read ${tok(u.lng("cache_read_tokens"))}")
                if (u.lng("cache_write_tokens") > 0) Chip("cache write ${tok(u.lng("cache_write_tokens"))}")
                Chip("out ${tok(u.lng("output_tokens"))}")
                if (u.lng("reasoning_tokens") > 0) Chip("reasoning ${tok(u.lng("reasoning_tokens"))}")
                u.dbl("tok_s")?.let { Chip("${it.toInt()} tok/s") }
                Chip("${u.lng("calls")} call${if (u.lng("calls") == 1L) "" else "s"}" + if (u.lng("sub_calls") > 0) " (${u.lng("sub_calls")} sub)" else "")
                Chip(costStr(u) ?: "cost n/a")
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
        Text("context ${(f * 100).toInt()}% of ${tok(max)}", style = mono(11f, 500, p.mu))
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
    val model = shortModel(turn?.str("model")?.ifBlank { null } ?: sess?.str("model") ?: "")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (model.isNotBlank()) Chip(model)
        sess?.let { s -> Chip("${tok(s.lng("prompt_tokens") + s.lng("output_tokens"))} tok") }
        sess?.let { s -> costStr(s)?.let { Chip(it) } }
        if (host.isNotBlank()) Chip(host)
    }
    if (turn != null) {
        ContextBar(turn, Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(12.dp))
        Text("THIS TURN", style = mono(10.5f, 600, p.mu))
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("in", tok(turn.lng("prompt_tokens")), Modifier.weight(1f))
            Stat("out", tok(turn.lng("output_tokens")), Modifier.weight(1f))
            Stat("cache hit", turn.dbl("cache_hit")?.let { "${(it * 100).toInt()}%" } ?: "–", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("tok/s", turn.dbl("tok_s")?.let { "${it.toInt()}" } ?: "–", Modifier.weight(1f))
            Stat("calls", turn.lng("calls").toString(), Modifier.weight(1f))
            Stat("cost", costStr(turn) ?: "n/a", Modifier.weight(1f))
        }
    }
    if (sess != null) {
        Spacer(Modifier.height(12.dp))
        Text("SESSION", style = mono(10.5f, 600, p.mu))
        Spacer(Modifier.height(4.dp))
        Text(buildList {
            add("${sess.lng("calls")} calls")
            add("${tok(sess.lng("prompt_tokens"))} in")
            add("${tok(sess.lng("output_tokens"))} out")
            sess.dbl("cache_hit")?.let { add("${(it * 100).toInt()}% cache") }
            add(costStr(sess) ?: "cost n/a")
        }.joinToString(" · "), style = mono(12f, 500, p.tx2))
    }
}

/** Chat header subtitle per the design: "opus-5.5 · 48.2k tok · $0.61". */
fun headerLine(sess: JSONObject?, turn: JSONObject?): String? {
    val s = sess ?: return turn?.let { usageLine(it) }?.ifBlank { null }
    return buildList {
        shortModel(turn?.str("model")?.ifBlank { null } ?: s.str("model")).takeIf { it.isNotBlank() }?.let { add(it) }
        val t = s.lng("prompt_tokens") + s.lng("output_tokens")
        if (t > 0) add("${tok(t)} tok")
        costStr(s)?.let { add(it) }
    }.joinToString(" · ").ifBlank { null }
}
