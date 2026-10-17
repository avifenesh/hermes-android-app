package dev.avifenesh.hermes

import android.icu.text.BreakIterator
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.Locale

/** Pure cell accounting; offsets and grapheme boundaries are UTF-16, never code-point indices. */
internal object TerminalCells {
    fun column(text: String, offset: Int, boundaries: List<Int>, widthOf: (Int) -> Int = ::terminalCodePointWidth): Int {
        var col = 0
        for ((start, end) in boundaries.zipWithNext()) {
            if (offset < end) return col
            col += width(text.substring(start, end), col, widthOf)
        }
        return col
    }
    fun width(cluster: String, column: Int = 0, widthOf: (Int) -> Int = ::terminalCodePointWidth): Int {
        if (cluster == "\t") return 8 - column % 8
        var width = 0
        var emoji = false
        var i = 0
        while (i < cluster.length) {
            val cp = Character.codePointAt(cluster, i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            if (cp == 0xFE0F || cp == 0x20E3 || cp in 0x1F1E6..0x1F1FF) emoji = true
            if (type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.FORMAT.toInt() ||
                Character.isISOControl(cp) || cp in 0x1F3FB..0x1F3FF) continue
            width = maxOf(width, widthOf(cp))
        }
        return if (emoji) maxOf(width, 2) else width
    }
}

internal fun terminalCodePointWidth(cp: Int): Int {
    val eastAsian = UCharacter.getIntPropertyValue(cp, UProperty.EAST_ASIAN_WIDTH)
    return if (eastAsian == UCharacter.EastAsianWidth.WIDE || eastAsian == UCharacter.EastAsianWidth.FULLWIDTH ||
        UCharacter.hasBinaryProperty(cp, UProperty.EMOJI_PRESENTATION)) 2 else 1
}

internal fun terminalGraphemes(text: String): List<Int> {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    val result = mutableListOf(iterator.first())
    var next = iterator.next()
    while (next != BreakIterator.DONE) { result.add(next); next = iterator.next() }
    return result
}

/** Shared with the debug fixture: snapshot is the tail response's `terminal` object.
 * Rows and columns are zero-based. Dragging only pans locally; it never sends input.
 * Deliberately no automatic scroll on refresh, including same-size alternate screens.
 */
@Composable
fun TerminalViewport(snapshot: JSONObject, onClick: (Int, Int) -> Unit, modifier: Modifier = Modifier) {
    val p = P
    val frame = snapshot.str("frame")
    val lines = remember(if (frame.isNotBlank()) frame else snapshot) { snapshot.optJSONArray("lines").strs() }
    val columns = snapshot.optInt("columns", 0)
    val rows = snapshot.optInt("rows", lines.size)
    val mouse = snapshot.optBoolean("mouse")
    var notice by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val horizontal = rememberScrollState()
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        lineHeight = 18.sp, color = p.tx2, textDirection = TextDirection.Ltr)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cellWidth = remember(measurer, style, density) {
        measurer.measure("M", style, softWrap = false).size.width.toFloat().coerceAtLeast(1f)
    }
    // Retain measurements only for the current screen: unchanged lines are cheap,
    // and a long-running session cannot accumulate a history-sized width cache.
    val widths = remember(measurer, style, density) { mutableMapOf<String, Int>() }
    val measuredWidth = remember(lines, style, density, columns, cellWidth) {
        widths.keys.retainAll(lines.toSet())
        val widest = lines.maxOfOrNull { line ->
            widths.getOrPut(line) { measurer.measure(line.ifEmpty { " " }, style, softWrap = false).size.width }
        } ?: 0
        with(density) { maxOf(widest.toFloat(), columns * cellWidth).toDp() + 24.dp }
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.background(p.s1)) {
            LazyColumn(Modifier.horizontalScroll(horizontal).width(maxOf(maxWidth, measuredWidth)).fillMaxHeight(), state = list,
                contentPadding = PaddingValues(12.dp)) {
                items(lines.size) { row ->
                    val line = lines[row]
                    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }
                    val boundaries = remember(line) { terminalGraphemes(line) }
                    Text(line.ifEmpty { " " }, softWrap = false,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                            lineHeight = 18.sp, color = p.tx2, textDirection = TextDirection.Ltr),
                        onTextLayout = { layout = it },
                        modifier = Modifier.fillParentMaxWidth().pointerInput(line, snapshot.str("frame"), columns, rows, mouse) {
                            detectTapGestures { point ->
                                if (!mouse) {
                                    if (columns > 0) notice = "Keyboard-only: use arrows + Enter"
                                    return@detectTapGestures
                                }
                                notice = null
                                val result = layout ?: return@detectTapGestures
                                if (row >= rows || columns <= 0) return@detectTapGestures
                                // TextLayout's hit is a caret boundary; use the glyph box to select
                                // the touched grapheme instead of the following cell's caret.
                                val offset = result.getOffsetForPosition(point).coerceIn(0, line.length)
                                val hit = if (offset > 0 && (offset == line.length ||
                                    point.x < result.getBoundingBox(offset).left)) offset - 1 else offset
                                val col = if (line.isEmpty()) (point.x / cellWidth).toInt()
                                    else if (point.x >= result.getLineRight(0))
                                        TerminalCells.column(line, line.length, boundaries) + ((point.x - result.getLineRight(0)) / cellWidth).toInt()
                                    else TerminalCells.column(line, hit, boundaries)
                                if (col in 0 until columns) onClick(row, col)
                            }
                        })
                }
            }
            if (notice != null) Text(notice!!, style = sans(12f, 600, p.att), modifier = Modifier
                .align(Alignment.BottomStart).background(p.s1).padding(8.dp))
        }
    }
}

@Composable
private fun TerminalButton(label: String, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    val p = P
    val active = selected && enabled
    Box(modifier = Modifier.clip(RoundedCornerShape(12.dp))
        .background(if (active) p.acc else p.s2)
        .border(1.dp, if (active) p.acc else p.line, RoundedCornerShape(12.dp))
        .semantics { this.selected = active }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center) {
        Text(label, style = sans(13f, 600, if (!enabled) p.mu else if (active) p.ink else p.tx),
            maxLines = 1, softWrap = false)
    }
}

/** Callback only; no auth/network. One-shot modifiers apply to the next named key only. */
@Composable
fun TerminalControls(onInput: (JSONObject) -> Unit, busy: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    var shift by rememberSaveable { mutableStateOf(false) }
    var ctrl by rememberSaveable { mutableStateOf(false) }
    var alt by rememberSaveable { mutableStateOf(false) }
    fun key(value: String) {
        if (busy) return
        val input = JSONObject().put("action", "key").put("key", value)
            .put("shift", shift).put("ctrl", ctrl).put("alt", alt)
        shift = false; ctrl = false; alt = false
        onInput(input)
    }
    val extras: @Composable () -> Unit = {
        if (!compact) TerminalButton("Shift", !busy, shift) { shift = !shift }
        TerminalButton("Ctrl", !busy, ctrl) { ctrl = !ctrl }
        TerminalButton("Alt", !busy, alt) { alt = !alt }
        for (number in 1..9) TerminalButton(number.toString(), !busy) { key(number.toString()) }
        TerminalButton("Space", !busy) { key("space") }
        TerminalButton("⌫", !busy) { key("backspace") }
        TerminalButton("Home", !busy) { key("home") }
        TerminalButton("End", !busy) { key("end") }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TerminalButton("Esc", !busy) { key("escape") }
            TerminalButton("Enter", !busy) { key("enter") }
            if (compact) TerminalButton("Shift", !busy, shift) { shift = !shift }
            for ((label, value) in listOf("Tab" to "tab", "↑" to "up", "↓" to "down",
                "←" to "left", "→" to "right", "PgUp" to "page_up", "PgDn" to "page_down")) TerminalButton(label, !busy) { key(value) }
            TerminalButton("Scroll up", !busy) { onInput(JSONObject().put("action", "scroll").put("direction", "up").put("amount", 3)) }
            TerminalButton("Scroll down", !busy) { onInput(JSONObject().put("action", "scroll").put("direction", "down").put("amount", 3)) }
            if (compact) extras()
        }
        if (!compact) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) { extras() }
    }
}

/** Legacy tmux/older gateways retain their existing interrupt path. */
@Composable
fun LegacyAgentInterrupt(canStop: Boolean, terminalAvailable: Boolean, busy: Boolean, onStop: () -> Unit) {
    if (canStop && !terminalAvailable) TerminalButton("Esc", !busy, onClick = onStop)
}

@Composable
fun FleetQuickReplies(busy: Boolean, onSend: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (reply in listOf("go ahead", "continue", "yes", "no", "status?", "commit and open a PR"))
            Chip(reply, false) { if (!busy) onSend(reply) }
    }
}

/** Send stays labelled and visible, including with an empty draft or closed keyboard. */
@Composable
fun FleetComposer(text: String, onText: (String) -> Unit, onSend: () -> Unit, busy: Boolean,
                  label: String = "Reply to agent", modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        InputBox(text, onText, label, Modifier.weight(1f), maxLines = if (compact) 1 else 3)
        Spacer(Modifier.width(8.dp))
        TerminalButton(if (busy) "Sending…" else "Send", !busy && text.isNotBlank(), selected = true, onClick = onSend)
    }
}
