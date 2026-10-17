@file:OptIn(ExperimentalFoundationApi::class)

package dev.avifenesh.hermes

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- slash commands
/** Ranked matches for a leading "/token": name prefix, then alias prefix, then substring. */
fun slashHits(text: String, cmds: List<JSONObject>, limit: Int = 8): List<JSONObject> {
    if (!text.startsWith("/") || text.any { it == ' ' || it == '\n' }) return emptyList()
    val q = text.substring(1).lowercase()
    fun rank(c: JSONObject): Int {
        val n = c.str("name").lowercase()
        val al = c.optJSONArray("aliases").strs().map { it.lowercase() }
        return when {
            n == q -> 0
            n.startsWith(q) -> 1
            al.any { it.startsWith(q) } -> 2
            q.length >= 2 && n.contains(q) -> 3
            q.length >= 3 && c.str("description").lowercase().contains(q) -> 4
            else -> 9
        }
    }
    return cmds.map { it to rank(it) }.filter { it.second < 9 }.sortedBy { it.second }.take(limit).map { it.first }
}

@Composable
fun SlashSuggest(text: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = P
    val cmds by Hub.cmds.collectAsState()
    LaunchedEffect(text.startsWith("/")) { if (text.startsWith("/")) runCatching { Hub.loadCmds() } }
    val hits = remember(text, cmds) { slashHits(text, cmds) }
    if (hits.isEmpty()) return
    Column(modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(16.dp)).background(p.s2).border(1.dp, p.line, RoundedCornerShape(16.dp))
        .verticalScroll(rememberScrollState())) {
        for (c in hits) {
            val name = c.str("name")
            val args = c.str("args")
            Column(Modifier.fillMaxWidth().clickable { onPick("/$name ") }.padding(horizontal = 14.dp, vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("/$name", style = mono(14f, 600, p.acct))
                    if (args.isNotBlank()) { Spacer(Modifier.width(8.dp)); Text(args, style = mono(12f, 400, p.mu), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                    val cat = c.str("category")
                    if (cat == "Skill" || cat == "Plugin") { Spacer(Modifier.weight(1f)); Text(cat.lowercase(), style = mono(11f, 500, p.dim)) }
                }
                val d = c.str("description")
                if (d.isNotBlank()) Text(d, style = sans(13f, 400, p.tx2), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------- helpers
val AttachmentsSaver = androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<JSONObject>, String>(
    save = { list -> list.map { it.toString() } },
    restore = { values -> androidx.compose.runtime.mutableStateListOf<JSONObject>().apply { addAll(values.map { JSONObject(it) }) } })

val JsonSaver = androidx.compose.runtime.saveable.Saver<JSONObject, String>(save = { it.toString() }, restore = { JSONObject(it) })

fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray?.strs(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

fun rel(ts: Double): String {
    if (ts <= 0) return ""
    val d = nowS() - ts
    val date = Date((ts * 1000).toLong())
    return when {
        d < 45 -> "now"
        d < 3600 -> "${(d / 60).toInt().coerceAtLeast(1)}m"
        d < 86400 -> "${(d / 3600).toInt()}h"
        d < 6 * 86400 -> SimpleDateFormat("EEE", Locale.getDefault()).format(date)
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(date)
    }
}

fun clock(ts: Double): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date((ts * 1000).toLong()))

/** Elapsed time that cannot be mistaken for a clock: 0:42 is fine, but "25:25" looks like a time of day, so from 10 min on it reads "25m", "1h 05m". */
fun span(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    return when {
        s < 600 -> "%d:%02d".format(s / 60, s % 60)
        s < 3600 -> "${s / 60}m"
        else -> "%dh %02dm".format(s / 3600, (s / 60) % 60)
    }
}

fun until(ts: Double): String {
    val d = ts - nowS()
    if (d <= 0) return "due"
    return when {
        d < 3600 -> "in ${(d / 60).toInt().coerceAtLeast(1)}m"
        d < 86400 -> "in ${(d / 3600).toInt()}h ${((d % 3600) / 60).toInt()}m"
        else -> SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date((ts * 1000).toLong()))
    }
}

fun parseTime(v: Any?): Double? {
    if (v == null || v == JSONObject.NULL) return null
    if (v is Number) return v.toDouble()
    val s = v.toString().trim()
    if (s.isEmpty() || s == "null") return null
    s.toDoubleOrNull()?.let { return it }
    runCatching { return OffsetDateTime.parse(s).toEpochSecond().toDouble() }
    runCatching { return LocalDateTime.parse(s.replace(' ', 'T')).atZone(ZoneId.systemDefault()).toEpochSecond().toDouble() }
    return null
}

fun money(x: Double): String = if (x > 0 && x < 0.01) "$%.3f".format(x) else "$%.2f".format(x)

fun sizeStr(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${n / 1024} KB"
    else -> "%.1f MB".format(n / 1048576.0)
}

@Composable
fun rememberTick(ms: Long = 1000): Long {
    var t by remember { mutableLongStateOf(System.currentTimeMillis()) }
    VisibleEffect(ms) {
        t = System.currentTimeMillis()
        while (true) { delay(ms); t = System.currentTimeMillis() }
    }
    return t
}

@Composable
fun Elapsed(since: Double, style: androidx.compose.ui.text.TextStyle) {
    val t = rememberTick()
    Text(span((t / 1000.0 - since).toLong()), style = style)
}

// ---------------------------------------------------------------- media
fun mediaDir(c: Context) = File(c.cacheDir, "media").apply { mkdirs() }
fun newMediaFile(c: Context, prefix: String, ext: String) = File(mediaDir(c), "$prefix-${System.currentTimeMillis()}$ext")

suspend fun uploadUri(c: Context, u: Uri, kind: String? = null, generation: Long = Api.epoch.capture()): JSONObject {
    Api.epoch.commit(generation) { }
    val cr = c.contentResolver
    val mime = cr.getType(u) ?: "application/octet-stream"
    var name = "file"
    runCatching { cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name } }
    val bytes = withContext(Dispatchers.IO) { cr.openInputStream(u)!!.use { it.readBytes() } }
    val o = Api.upload(name, mime, bytes, generation)
    o.put("kind", kind ?: when { mime.startsWith("image/") -> "image"; mime.startsWith("audio/") -> "voice"; else -> "file" })
    return o
}

suspend fun uploadFile(f: File, mime: String, kind: String): JSONObject {
    val generation = Api.epoch.capture()
    val bytes = withContext(Dispatchers.IO) { f.readBytes() }
    return Api.upload(f.name, mime, bytes, generation).put("kind", kind)
}

suspend fun fetchMedia(c: Context, name: String, fileName: String?, size: Long? = null, progress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
    // Cache key is the server media id: two "app-release.apk" attachments must not collide.
    val f = File(mediaDir(c), name.replace('/', '_') + "_" + (fileName?.takeIf { it.isNotBlank() } ?: name).replace('/', '_'))
    val expectedSize = size?.takeIf { it >= 0 }
    if (!f.exists() || f.length() == 0L || (expectedSize != null && f.length() != expectedSize))
        Api.download("/v1/media/" + Uri.encode(name), f, expectedSize, progress)
    f
}

const val APK_MIME = "application/vnd.android.package-archive"

/** Copies a fetched attachment into the public Downloads folder (MediaStore, no storage permission on API 29+). */
suspend fun saveToDownloads(c: Context, f: File, name: String, mime: String?): Unit = withContext(Dispatchers.IO) {
    val cv = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
        put(android.provider.MediaStore.Downloads.MIME_TYPE, mime?.ifBlank { null } ?: "application/octet-stream")
        put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
    }
    val r = c.contentResolver
    val uri = r.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IllegalStateException("Downloads unavailable")
    try {
        r.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
        r.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    } catch (e: Exception) { r.delete(uri, null, null); throw e }
}

class Recorder(c: Context) {
    val file: File = newMediaFile(c, "voice", ".ogg")
    val started = System.currentTimeMillis()
    private val r = MediaRecorder(c)
    private var recording = false
    init {
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            r.setAudioSamplingRate(48000)
            r.setAudioEncodingBitRate(32000)
            r.setOutputFile(file.absolutePath)
            r.prepare(); r.start(); recording = true
        } catch (e: Exception) { r.release(); file.delete(); throw e }
    }
    fun stop(): File? {
        if (!recording) return null
        recording = false
        return try { r.stop(); file } catch (e: Exception) { file.delete(); null } finally { r.release() }
    }
    fun cancel() { if (recording) { stop(); file.delete() } }
}

object Player {
    private var mp: MediaPlayer? = null
    var playing = mutableStateOf<String?>(null)
    fun toggle(key: String, f: File) {
        val cur = playing.value
        mp?.release(); mp = null; playing.value = null
        if (cur == key) return
        mp = MediaPlayer().apply { setDataSource(f.absolutePath); setOnCompletionListener { playing.value = null; it.release(); if (mp === it) mp = null }; prepare(); start() }
        playing.value = key
    }
}

object Img {
    val cache = LruCache<String, ImageBitmap>(40)
    suspend fun load(key: String, remote: Boolean): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(key)?.let { return@withContext it }
        val bytes = if (remote) Api.plain.newCall(Request.Builder().url(key).build()).execute().use { it.body.bytes() }
        else Api.bytes("/v1/media/" + Uri.encode(key))
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (o.outWidth / s > 1600 || o.outHeight / s > 1600) s *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return@withContext null
        bmp.asImageBitmap().also { cache.put(key, it) }
    }
}

@Composable
fun RemoteImage(key: String, remote: Boolean = false, modifier: Modifier = Modifier) {
    var bmp by remember(key) { mutableStateOf(Img.cache.get(key)) }
    var failed by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) { if (bmp == null) runCatching { bmp = Img.load(key, remote) }.onFailure { failed = true } }
    val b = bmp
    if (b != null) {
        Image(b, null, modifier.widthIn(max = 320.dp).heightIn(max = 420.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Fit)
    } else {
        Box(modifier.width(220.dp).height(160.dp).clip(RoundedCornerShape(16.dp)).background(P.s2), contentAlignment = Alignment.Center) {
            Text(if (failed) "Image unavailable" else "Loading…", style = mono(12f, 500, P.mu))
        }
    }
}

// ---------------------------------------------------------------- markdown
sealed interface Blk
data class BPara(val t: String) : Blk
data class BHead(val lvl: Int, val t: String) : Blk
data class BCode(val lang: String, val t: String) : Blk
data class BItem(val mark: String, val t: String, val depth: Int) : Blk
data class BQuote(val t: String) : Blk
data class BTable(val rows: List<List<String>>) : Blk
data object BRule : Blk

private val reHead = Regex("^(#{1,6})\\s+(.*)$")
private val reItem = Regex("^(\\s*)([-*+•]|\\d{1,3}[.)])\\s+(.*)$")
private val reRule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
private val reSep = Regex("^:?-{2,}:?$")

fun parseMd(src: String): List<Blk> {
    val out = ArrayList<Blk>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += BPara(para.toString().trimEnd()); para.setLength(0) }
    val lines = src.replace("\r\n", "\n").split("\n")
    var i = 0
    while (i < lines.size) {
        val l = lines[i]
        val tl = l.trimStart()
        if (tl.startsWith("```")) {
            flush()
            val lang = tl.removePrefix("```").trim()
            val sb = StringBuilder(); i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) { sb.append(lines[i]).append('\n'); i++ }
            out += BCode(lang, sb.toString().trimEnd('\n')); i++; continue
        }
        if (tl.startsWith("|") && tl.count { it == '|' } >= 2) {
            flush()
            val rows = ArrayList<List<String>>()
            while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                val cells = lines[i].trim().trim('|').split("|").map { it.trim() }
                if (!cells.all { reSep.matches(it) }) rows += cells
                i++
            }
            out += BTable(rows); continue
        }
        val h = reHead.find(l)
        val li = reItem.find(l)
        when {
            l.isBlank() -> flush()
            h != null -> { flush(); out += BHead(h.groupValues[1].length, h.groupValues[2].trim().trimEnd('#').trim()) }
            reRule.matches(l) -> { flush(); out += BRule }
            li != null -> { flush(); val mk = li.groupValues[2]; out += BItem(if (mk[0].isDigit()) mk else "•", li.groupValues[3], li.groupValues[1].length / 2) }
            tl.startsWith(">") -> { flush(); out += BQuote(tl.removePrefix(">").trim()) }
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(l) }
        }
        i++
    }
    flush()
    return out
}

fun safeLink(u: String): Boolean = u.startsWith("https://", true) || u.startsWith("http://", true) || u.startsWith("mailto:", true)

// Delimiter matching is indexed once per render range, never rescanned from each
// opener. Three reverse emphasis tables skip code/links and nested bold in O(1).
// Each pass is O(n) time/space; the existing literal-fallback nesting bound makes
// rendering O(32 * n), including substring/index construction, on untrusted text.
internal data class InlineLink(val labelEnd: Int, val urlStart: Int, val urlEnd: Int, val end: Int)

internal class InlineDelimiters(private val s: String) {
    // Deterministic work counter for adversarial-input regression tests.
    var indexedSteps: Long = 0
        private set
    val tickLength = IntArray(s.length)
    val codeEnd = IntArray(s.length) { -1 }
    private val labelEnd = IntArray(s.length) { -1 }
    private val parenEnd = IntArray(s.length) { -1 }
    private val emphasis = Array(3) { IntArray(s.length + 1) { -1 } }

    init {
        // Maximal tick runs only: unmatched runs are emitted as one literal token.
        // Equal-length runs are paired using the next run on the same line.
        var i = 0
        while (i < s.length) {
            indexedSteps++
            if (s[i] != '`') { i++; continue }
            val start = i
            while (i < s.length && s[i] == '`') { indexedSteps++; i++ }
            tickLength[start] = i - start
        }
        val nextTick = IntArray(s.length + 1)
        val tickLine = IntArray(s.length + 1) { -1 }
        var line = 0
        for (at in s.lastIndex downTo 0) {
            indexedSteps++
            if (s[at] == '\n') line++
            val count = tickLength[at]
            if (count > 0) {
                if (tickLine[count] == line) codeEnd[at] = nextTick[count] + count
                nextTick[count] = at
                tickLine[count] = line
            }
        }
        // Each bracket/parenthesis is pushed and popped at most once. Destinations
        // stop at whitespace; labels stop at newlines and ignore escapes/code.
        val brackets = ArrayList<Int>()
        i = 0
        while (i < s.length) {
            indexedSteps++
            when {
                s[i] == '\n' -> brackets.clear()
                s[i] == '\\' -> { i += 2; continue }
                codeEnd[i] > i -> { i = codeEnd[i]; continue }
                s[i] == '[' -> brackets.add(i)
                s[i] == ']' && brackets.isNotEmpty() -> labelEnd[brackets.removeAt(brackets.lastIndex)] = i
            }
            i++
        }
        val parens = ArrayList<Int>()
        for (at in s.indices) {
            indexedSteps++
            when {
                s[at].isWhitespace() -> parens.clear()
                s[at] == '(' -> parens.add(at)
                s[at] == ')' && parens.isNotEmpty() -> parenEnd[parens.removeAt(parens.lastIndex)] = at
            }
        }
        // A table entry is the next closing delimiter at/after this position.
        // All dependencies point rightward, so even failed searches are O(1).
        for (at in s.lastIndex downTo 0) {
            val target = linkAt(at)
            for (width in 1..3) {
                indexedSteps++
                val table = emphasis[width - 1]
                fun after(end: Int): Int = table[end.coerceAtMost(s.length)]
                val skip = when {
                    s[at] == '\n' -> -1
                    s[at] == '\\' -> after(at + 2)
                    codeEnd[at] > at -> after(codeEnd[at])
                    target != null -> after(target.end)
                    // A complete nested triple run belongs to its own span, not
                    // to a surrounding single/double-star closer.
                    width < 3 && s.startsWith("***", at) && canOpen(at, 3) &&
                        emphasisEnd(at, 3) > at + 3 -> after(emphasisEnd(at, 3) + 3)
                    // Recognize nested bold BEFORE testing the preceding space.
                    width == 1 && s.startsWith("**", at) -> {
                        if (s.startsWith("***", at) && at > 0 && !s[at - 1].isWhitespace()) at
                        else {
                            val end = emphasisEnd(at, 2)
                            after(if (end > at + 2 && canOpen(at, 2)) end + 2 else at + 2)
                        }
                    }
                    // In **outer *inner*** the first final star closes italic;
                    // the remaining two close bold, rather than eating italic.
                    width == 2 && s[at] == '*' && !s.startsWith("**", at) && canOpen(at, 1) -> {
                        val end = emphasisEnd(at, 1)
                        if (end > at + 1) after(end + 1) else after(at + 1)
                    }
                    s.startsWith(when (width) { 1 -> "*"; 2 -> "**"; else -> "***" }, at) &&
                        at > 0 && !s[at - 1].isWhitespace() -> at
                    else -> after(at + 1)
                }
                table[at] = skip
            }
        }
    }

    private fun canOpen(start: Int, width: Int): Boolean =
        start + width < s.length && !s[start + width].isWhitespace()

    fun emphasisEnd(start: Int, width: Int): Int =
        emphasis[width - 1][(start + width).coerceAtMost(s.length)]

    fun linkAt(start: Int): InlineLink? {
        val close = labelEnd[start]
        if (close < 0 || s.getOrNull(close + 1) != '(') return null
        val end = parenEnd[close + 1]
        return if (end < 0) null else InlineLink(close, close + 2, end, end + 1)
    }
}

private fun inlineUrlEnd(s: String, start: Int): Int {
    var i = start
    var parens = 0
    while (i < s.length && !s[i].isWhitespace() && s[i] !in "<>[]`*\"'") {
        if (s[i] == '(') parens++
        if (s[i] == ')') { if (parens == 0) break; parens-- }
        i++
    }
    while (i > start && s[i - 1] in ".,;:!?") i--
    return i
}

fun inline(s: String, p: Pal): AnnotatedString = buildAnnotatedString {
    val link = TextLinkStyles(SpanStyle(color = p.acct, textDecoration = TextDecoration.Underline))
    fun render(text: String, allowLinks: Boolean = true, depth: Int = 0) {
        // Bound recursion for untrusted, deeply nested model output.
        if (depth >= 32) { append(text); return }
        val delimiters = InlineDelimiters(text)
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && text.getOrNull(i + 1) in listOf('*', '`', '[', ']', '(', ')', '\\')) {
                append(text[i + 1]); i += 2; continue
            }
            if (text[i] == '`') {
                val end = delimiters.codeEnd[i]
                if (end > i) {
                    val ticks = delimiters.tickLength[i]
                    withStyle(SpanStyle(fontFamily = Mono, background = p.s3, fontSize = 0.86.em)) {
                        append("\u2009" + text.substring(i + ticks, end - ticks) + "\u2009")
                    }
                    i = end; continue
                }
                val ticks = delimiters.tickLength[i]
                if (ticks > 0) { append(text.substring(i, i + ticks)); i += ticks; continue }
            }
            if (text[i] == '[') {
                val target = delimiters.linkAt(i)
                if (target != null) {
                    val label = text.substring(i + 1, target.labelEnd)
                    val url = text.substring(target.urlStart, target.urlEnd)
                    // Keep the existing whitelist; never activate app/deep-link schemes.
                    if (allowLinks && safeLink(url)) {
                        withLink(LinkAnnotation.Url(url, link)) { render(label, false, depth + 1) }
                    } else {
                        render(label, false, depth + 1)
                        append(" ($url)")
                    }
                    i = target.end; continue
                }
            }
            if (text[i] == '*') {
                val marker = when {
                    text.startsWith("***", i) -> "***"
                    text.startsWith("**", i) -> "**"
                    else -> "*"
                }
                val start = i + marker.length
                if (start < text.length && !text[start].isWhitespace()) {
                    val end = delimiters.emphasisEnd(i, marker.length)
                    if (end > start) {
                        withStyle(SpanStyle(
                            fontWeight = if (marker.length >= 2) FontWeight.SemiBold else null,
                            fontStyle = if (marker.length != 2) FontStyle.Italic else null,
                        )) { render(text.substring(start, end), allowLinks, depth + 1) }
                        i = end + marker.length; continue
                    }
                }
            }
            if (allowLinks && (i == 0 || (!text[i - 1].isLetterOrDigit() && text[i - 1] !in "_:")) &&
                (text.startsWith("https://", i, true) || text.startsWith("http://", i, true))) {
                val end = inlineUrlEnd(text, i)
                val url = text.substring(i, end)
                if (safeLink(url)) withLink(LinkAnnotation.Url(url, link)) { append(url) }
                else append(url)
                i = end; continue
            }
            append(text[i]); i++
        }
    }
    render(s)
}

@Composable
fun Md(text: String, color: Color = P.tx, size: Float = 15.5f) {
    val p = P
    val blocks = remember(text) { parseMd(text) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        for (b in blocks) when (b) {
            is BPara -> Text(inline(b.t, p), style = sans(size, 400, color, size * 1.5f))
            is BHead -> Text(inline(b.t, p), style = if (b.lvl <= 2) serif(if (b.lvl == 1) 28f else 24f, color) else sans(size + 1f, 650, color))
            is BItem -> Row(Modifier.padding(start = (b.depth * 14).dp)) {
                Text(b.mark, style = sans(size, 500, p.mu, size * 1.5f), modifier = Modifier.width(if (b.mark == "•") 16.dp else 26.dp))
                Text(inline(b.t, p), style = sans(size, 400, color, size * 1.5f))
            }
            is BQuote -> Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(p.line2))
                Spacer(Modifier.width(10.dp))
                Text(inline(b.t, p), style = sans(size, 400, p.tx2, size * 1.5f))
            }
            is BCode -> CodeBlock(b.t, b.lang)
            is BTable -> TableBlock(b.rows)
            is BRule -> Box(Modifier.fillMaxWidth().height(1.dp).background(p.line2))
        }
    }
}

@Composable
fun CodeBlock(code: String, lang: String = "") {
    val cb = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.s2).border(1.dp, P.line, RoundedCornerShape(14.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(lang.ifBlank { "code" }, style = mono(11f, 500, P.mu))
            Spacer(Modifier.weight(1f))
            Text("copy", style = mono(11f, 500, P.mu), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { cb.setText(AnnotatedString(code)); Ui.say("Copied") }.padding(8.dp))
        }
        Text(code, style = mono(13f, 400, P.tx), softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
    }
}

@Composable
fun TableBlock(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    val p = P
    val n = rows.maxOf { it.size }
    val w = (0 until n).map { c -> ((rows.maxOf { it.getOrNull(c)?.length ?: 0 }).coerceIn(3, 30) * 7.6f + 20).dp }
    Column(Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, p.line2, RoundedCornerShape(12.dp)).horizontalScroll(rememberScrollState())) {
        rows.forEachIndexed { r, row ->
            Row(Modifier.background(if (r == 0) p.s2 else Color.Transparent)) {
                for (c in 0 until n) Text(inline(row.getOrNull(c) ?: "", p), style = sans(13.5f, if (r == 0) 600 else 400, p.tx),
                    modifier = Modifier.width(w[c]).padding(horizontal = 10.dp, vertical = 7.dp))
            }
            if (r < rows.lastIndex) Box(Modifier.height(1.dp).width(w.fold(0.dp) { a, b -> a + b }).background(p.line))
        }
    }
}

// ---------------------------------------------------------------- components
@Composable
fun MarkBox(size: Dp = 30.dp, bg: Color = P.acc) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(bg), contentAlignment = Alignment.Center) {
        Icon(Ic.mark, null, tint = P.ink, modifier = Modifier.size(size * 0.62f))
    }
}

@Composable
fun Icn(v: ImageVector, tint: Color = P.tx, size: Dp = 22.dp, modifier: Modifier = Modifier) = Icon(v, null, tint = tint, modifier = modifier.size(size))

/** Shared defaults for icon-only controls; callers can supply a contextual label. */
fun iconLabel(v: ImageVector): String = when (v) {
    Ic.back -> "Back"
    Ic.x -> "Close"
    Ic.plus -> "Add"
    Ic.send -> "Send"
    Ic.stop -> "Stop"
    Ic.play -> "Run"
    Ic.mic -> "Microphone"
    Ic.camera -> "Camera"
    Ic.search -> "Search"
    Ic.refresh -> "Refresh"
    Ic.more -> "More options"
    Ic.trash -> "Delete"
    Ic.edit -> "Edit"
    Ic.pin -> "Pin"
    Ic.download -> "Download"
    Ic.chevR -> "Next"
    Ic.chevD -> "Expand"
    Ic.chevU -> "Collapse"
    Ic.pen -> "New chat"
    Ic.sliders -> "Settings"
    Ic.link -> "Link"
    Ic.file -> "File"
    Ic.check -> "Confirm"
    Ic.power -> "Power"
    else -> "Open"
}

@Composable
fun IconBtn(v: ImageVector, tint: Color = P.tx, size: Dp = 44.dp, bg: Color = Color.Transparent, enabled: Boolean = true, label: String = iconLabel(v), onClick: () -> Unit) {
    Box(Modifier.size(maxOf(size, 48.dp)).clip(CircleShape).semantics { contentDescription = label }.clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).clip(CircleShape).background(bg).alpha(if (enabled) 1f else 0.4f),
            contentAlignment = Alignment.Center) { Icn(v, tint, size * 0.5f) }
    }
}

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = P
    val haptic = LocalHapticFeedback.current
    val x by animateDpAsState(if (on) 21.dp else 3.dp, label = "toggle")
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).toggleable(value = on, enabled = enabled, role = Role.Switch) { value ->
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onChange(value)
    }.alpha(if (enabled) 1f else 0.45f), contentAlignment = Alignment.Center) {
        Box(Modifier.size(44.dp, 26.dp).clip(RoundedCornerShape(13.dp)).background(if (on) p.acc else p.s3)) {
            Box(Modifier.offset(x = x, y = 3.dp).size(20.dp).clip(CircleShape).background(if (on) p.ink else p.mu))
        }
    }
}

@Composable
fun HDialog(title: String, onDismiss: () -> Unit, body: @Composable ColumnScope.() -> Unit, actions: @Composable RowScope.() -> Unit) {
    val p = P
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.s1).border(1.dp, p.line, RoundedCornerShape(24.dp)).padding(22.dp)) {
            Text(title, style = serif(24f, p.tx))
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = body)
            Spacer(Modifier.height(20.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}

@Composable
fun Header(title: String, sub: String? = null, meta: (@Composable () -> Unit)? = null, right: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = serif(46f, P.tx))
            if (meta != null) meta()
            if (sub != null) Text(sub, style = sans(13.5f, 400, P.mu))
        }
        right()
    }
}

@Composable
fun TopBar(title: String, sub: String? = null, subColor: Color = P.mu, right: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().background(P.bg)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 62.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.back) { Ui.pop() }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(title, style = sans(17f, 600, P.tx), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub != null) Text(sub, style = mono(12f, 500, subColor), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            right()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(P.line))
    }
}

@Composable
fun Section(title: String, right: String? = null, color: Color = P.mu) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = mono(11.5f, 600, color).copy(letterSpacing = 1.2.em * 0.1f), modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        if (right != null) Text(right, style = mono(11.5f, 500, P.mu))
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, border: Color = P.line, bg: Color = P.s1, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(22.dp)).background(bg).border(1.dp, border, RoundedCornerShape(22.dp)), content = content)
}

@Composable
fun Chip(text: String, selected: Boolean, count: Int? = null, onClick: () -> Unit) {
    val p = P
    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(if (selected) p.acc else p.s2).selectable(selected = selected, role = Role.Tab, onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = sans(13.5f, 600, if (selected) p.ink else p.tx2))
        if (count != null && count > 0) {
            Spacer(Modifier.width(6.dp))
            Text(if (count > 99) "99+" else count.toString(), style = mono(11.5f, 600, if (selected) p.ink else p.mu))
        }
    }
}

/** Human day label for chat separators (e.g. "Thu 8 Oct"). */
fun dayLabel(ts: Double): String = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date((ts * 1000).toLong()))

@Composable
fun Pill(text: String, fg: Color, bg: Color) {
    Text(text, style = mono(11f, 600, fg), modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 7.dp, vertical = 3.dp))
}

@Composable
fun Btn(text: String, modifier: Modifier = Modifier, primary: Boolean = true, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val p = P
    val bg = when { danger -> p.badd; primary -> p.acc; else -> p.s3 }
    val fg = when { danger -> p.bad; primary -> p.ink; else -> p.tx }
    val hf = LocalHapticFeedback.current
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp)).background(bg).clickable(enabled = enabled, role = Role.Button) {
        hf.performHapticFeedback(if (danger) HapticFeedbackType.Reject else if (primary) HapticFeedbackType.Confirm else HapticFeedbackType.VirtualKey); onClick() }.alpha(if (enabled) 1f else 0.45f)
        .padding(horizontal = 18.dp, vertical = 10.dp), contentAlignment = Alignment.Center) { Text(text, style = sans(15f, 600, fg), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
}

@Composable
fun Seg(opts: List<Pair<String, String>>, sel: String, on: (String) -> Unit) {
    val p = P
    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(p.s2).padding(3.dp)) {
        for ((k, label) in opts) Text(label, style = sans(13.5f, 600, if (k == sel) p.tx else p.mu), modifier = Modifier.clip(RoundedCornerShape(11.dp))
            .background(if (k == sel) p.s4 else Color.Transparent).selectable(selected = k == sel, role = Role.Tab) { on(k) }.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 12.dp))
    }
}

@Composable
fun LiveDot(size: Dp = 8.dp, color: Color = P.acc) {
    val a by rememberInfiniteTransition(label = "live").animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(size).alpha(a).clip(CircleShape).background(color))
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun InputBox(v: String, on: (String) -> Unit, ph: String, modifier: Modifier = Modifier, minLines: Int = 1, maxLines: Int = 6, bare: Boolean = false, size: Float = 16f,
             kb: KeyboardOptions = KeyboardOptions.Default) {
    val p = P
    BasicTextField(v, on, modifier = modifier.semantics { contentDescription = ph }, textStyle = sans(size, 400, p.tx, size * 1.42f), cursorBrush = SolidColor(p.acct), minLines = minLines, maxLines = maxLines, keyboardOptions = kb,
        decorationBox = { inner ->
            val m = if (bare) Modifier.padding(vertical = 6.dp) else Modifier.clip(RoundedCornerShape(22.dp)).background(p.s2).border(1.dp, p.line, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 11.dp)
            Box(m) { if (v.isEmpty()) Text(ph, style = sans(size, 400, p.dim, size * 1.42f)); inner() }
        })
}

@Composable
fun Empty(title: String, sub: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MarkBox(44.dp, P.s3)
        Spacer(Modifier.height(16.dp))
        Text(title, style = serif(28f, P.tx))
        Spacer(Modifier.height(6.dp))
        Text(sub, style = sans(14f, 400, P.mu).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
    }
}

@Composable
fun AttachmentView(a: JSONObject, mine: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val id = a.str("id").ifBlank { a.str("media") }
    val kind = a.str("kind")
    val mime = a.str("mime")
    when {
        kind == "image" || mime.startsWith("image/") -> RemoteImage(id)
        kind == "voice" || mime.startsWith("audio/") -> {
            val playing = Player.playing.value == id
            Row(Modifier.clip(RoundedCornerShape(18.dp)).background(if (mine) P.s4 else P.s2).clickable {
                scope.launch { runCatching { Player.toggle(id, fetchMedia(ctx, id, a.str("file_name"))) }.onFailure { Ui.say(it.message) } }
            }.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Icn(if (playing) Ic.stop else Ic.play, P.acct, 16.dp)
                Spacer(Modifier.width(10.dp))
                Text(if (playing) "Playing…" else "Voice message", style = sans(14f, 500, P.tx))
            }
        }
        else -> FileChip(a, id, mime, mine)
    }
}


@Composable
fun FileChip(a: JSONObject, id: String, mime: String, mine: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val name = a.str("file_name").ifBlank { id }
    val sz = a.optString("size").toLongOrNull()
    val apk = mime == APK_MIME || name.endsWith(".apk", true)
    var prog by remember(id) { mutableStateOf<Float?>(null) }
    fun get(then: suspend (File) -> Unit) {
        if (prog != null) return
        prog = 0f
        scope.launch {
            runCatching { then(fetchMedia(ctx, id, name, sz) { p -> prog = p }) }.onFailure { Ui.say("Download failed: " + it.message) }
            prog = null
        }
    }
    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(if (mine) P.s4 else P.s2).clickable { get { openFile(ctx, it, mime) } }
        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icn(Ic.file, P.tx2, 20.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(name, style = sans(14f, 500, P.tx), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val p = prog
            Text(when {
                p != null -> "Downloading… ${(p * 100).toInt()}%" + (sz?.let { " of " + sizeStr(it) } ?: "")
                else -> listOfNotNull(sz?.let { sizeStr(it) }, if (apk) "tap to install" else "tap to open").joinToString(" · ")
            }, style = mono(11f, 500, if (p != null) P.acct else P.mu))
            if (p != null) Box(Modifier.padding(top = 4.dp).width(150.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(P.s3)) {
                Box(Modifier.fillMaxWidth(p.coerceIn(0.02f, 1f)).height(3.dp).background(P.acc))
            }
        }
        Spacer(Modifier.width(4.dp))
        IconBtn(Ic.download, P.tx2, 40.dp) { get { saveToDownloads(ctx, it, name, mime); Ui.say("Saved to Downloads: $name") } }
    }
}

@Composable
fun ErrorPanel(msg: String, onRetry: () -> Unit) {
    val p = P
    Panel(Modifier.padding(vertical = 8.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(msg, style = sans(14f, 400, p.tx2))
            Btn("Try again", primary = false, onClick = onRetry)
        }
    }
}

@Composable
fun Skeleton(rows: Int = 4) {
    val p = P
    val a by rememberInfiniteTransition(label = "sk").animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "ska")
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).alpha(a), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(rows) { i ->
            Box(Modifier.fillMaxWidth(if (i % 2 == 0) 1f else 0.82f).height(56.dp).clip(RoundedCornerShape(16.dp)).background(p.s2))
        }
    }
}

@Composable
fun EmptyState(title: String, sub: String? = null) {
    val p = P
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = sans(16f, 600, p.tx2))
        if (sub != null) Text(sub, style = sans(13.5f, 400, p.mu), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun ListRow(lead: (@Composable () -> Unit)?, title: String, sub: String?, trail: (@Composable () -> Unit)?, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, titleColor: Color? = null) {
    val p = P
    val hfRow = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick?.let { l -> { hfRow.performHapticFeedback(HapticFeedbackType.LongPress); l() } }).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (lead != null) lead()
        Column(Modifier.weight(1f)) {
            Text(title, style = sans(15f, 600, titleColor ?: p.tx), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrEmpty()) Text(sub, style = sans(13f, 400, p.mu), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trail != null) trail()
    }
}


/** The run's Stop control: a 46dp red circle that takes Send's place in the composer while a run is live. */
@Composable
fun StopCircle(onClick: () -> Unit) {
    val p = P
    val hf = LocalHapticFeedback.current
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(p.bad).semantics { contentDescription = "Stop run" }
            .clickable { hf.performHapticFeedback(HapticFeedbackType.Reject); onClick() }, contentAlignment = Alignment.Center) {
            Icn(Ic.stop, Color.White, 18.dp)
        }
    }
}


/** An image from an outside URL: fetching it reveals the phone's IP to that host, so it loads only on tap. */
@Composable
fun TapToLoadImage(url: String) {
    var go by remember(url) { mutableStateOf(false) }
    if (go) { RemoteImage(url, remote = true); return }
    val host = runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: "link"
    Box(Modifier.width(220.dp).heightIn(min = 72.dp).clip(RoundedCornerShape(16.dp)).background(P.s2).clickable { go = true }.padding(14.dp),
        contentAlignment = Alignment.Center) {
        Text("Image from $host · tap to load", style = mono(12f, 500, P.tx2))
    }
}
