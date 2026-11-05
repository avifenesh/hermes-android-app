package dev.avifenesh.hermes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection

/*
 * Text direction rules. UI copy (translated strings, labels, lines the app composes) follows the layout direction:
 * that is the default of sans(), mono() and serif(). Text that comes from users, agents or the gateway keeps its own
 * direction (content()); code, commands, paths and terminal output are always left to right (ltr()). Content placed
 * inside a translated template or joined into a UI line goes through bidiIsolate().
 */

private const val FSI = '\u2068' // FIRST STRONG ISOLATE
private const val PDI = '\u2069' // POP DIRECTIONAL ISOLATE

private fun isParagraphSeparator(c: Char) = Character.getDirectionality(c) == Character.DIRECTIONALITY_PARAGRAPH_SEPARATOR

/**
 * [s] as a first-strong isolate: inside a translated template or a joined UI line it takes its direction from its own
 * first strong character, and its neutrals (an "@", a period, brackets, digits) stay with it instead of joining the
 * sentence around it. Isolate controls already in [s] are balanced so it cannot end the isolate early or leave one
 * open, and each line is isolated on its own because a paragraph break ends every isolate.
 */
fun bidiIsolate(s: String): String {
    if (s.isEmpty()) return s
    val out = StringBuilder(s.length + 4)
    var open = 0
    var inLine = false
    fun close() { if (inLine) { repeat(open) { out.append(PDI) }; out.append(PDI); open = 0; inLine = false } }
    for (c in s) {
        if (isParagraphSeparator(c)) { close(); out.append(c); continue }
        if (!inLine) { out.append(FSI); inLine = true }
        when (c) {
            '\u2066', '\u2067', FSI -> { open++; out.append(c) }
            PDI -> if (open > 0) { open--; out.append(c) } // an unmatched PDI would close this isolate
            else -> out.append(c)
        }
    }
    close()
    return out.toString()
}

/** [bidiIsolate] for each content part of a UI line, then joined: "Hermes Demo · default · opus 5.5". */
fun bidiJoin(parts: Iterable<String>, separator: String = " · "): String = parts.joinToString(separator) { bidiIsolate(it) }

/** Text from users, agents or the gateway: its first strong character sets its direction and its alignment. */
fun TextStyle.content(): TextStyle = copy(textDirection = TextDirection.Content)

/** Code, commands, paths, terminal output and cron syntax: always left to right, in any app language. */
fun TextStyle.ltr(): TextStyle = copy(textDirection = TextDirection.Ltr)

/**
 * One line of content inside UI chrome (a chat title in a row, a model or file name next to a value): the content keeps
 * its own direction, but the line aligns to the layout's start so it sits where the row starts, not against the value
 * at the row's other end.
 */
@Composable @ReadOnlyComposable
fun TextStyle.contentLine(): TextStyle = content().alignToLayoutStart()

/** Absolute alignment to the start of the current layout direction, whatever direction the text resolves to. */
@Composable @ReadOnlyComposable
fun TextStyle.alignToLayoutStart(): TextStyle =
    copy(textAlign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left)

/**
 * Short text (labels, row subtitles, buttons): Japanese and Chinese break between phrases, not between any two
 * characters ("覚えているこ/と"); API 33+. Lines still fill greedily: balancing (LineBreak.Heading) would narrow a
 * four-line row subtitle into an even, ragged block. Other scripts break as before.
 */
fun TextStyle.short(): TextStyle = copy(lineBreak = LineBreak.Simple.copy(wordBreak = LineBreak.WordBreak.Phrase))

/** Code blocks, terminals and other left-to-right surfaces: their scroll starts at the left and clips on the right. */
@Composable
fun LtrLayout(content: @Composable () -> Unit) = CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
