package dev.avifenesh.hermes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class PrivacyBlock(val text: String, val level: Int = 0)
private val privacyLink = Regex("\\[([^\\n]+?)\\]\\(([^\\n]+?)\\)")

/** Local policy formatting only. Links stay readable text, never browser or mail actions. */
private fun privacyText(text: String): String = privacyLink.replace(text) { match ->
    val (label, target) = match.destructured
    if (target == label || target == "mailto:$label") label else "$label ($target)"
}.replace("**", "").replace("`", "")

private fun privacyBlocks(source: String): List<PrivacyBlock> = parseMd(source).flatMap { block ->
    when (block) {
        is BHead -> listOf(PrivacyBlock(privacyText(block.t), block.lvl))
        is BPara -> listOf(PrivacyBlock(privacyText(block.t)))
        is BItem -> listOf(PrivacyBlock("${block.mark} ${privacyText(block.t)}"))
        is BQuote -> listOf(PrivacyBlock(privacyText(block.t)))
        is BCode -> listOf(PrivacyBlock(block.t))
        is BRule -> emptyList()
        // Stack table cells with their column labels; never force a wide horizontal table
        // on compact screens or make TalkBack infer a cell's meaning from another row.
        is BTable -> {
            val labels = block.rows.firstOrNull().orEmpty()
            block.rows.drop(1).flatMap { row ->
                row.mapIndexed { index, cell ->
                    if (index == 0) PrivacyBlock(privacyText(cell), 3)
                    else PrivacyBlock(privacyText("${labels.getOrElse(index) { "Details" }}: $cell"))
                }
            }
        }
    }
}

/** Same packaged policy before pairing and in Me. No credentials, account or network. */
@Composable
fun PrivacyScreen() {
    val context = LocalContext.current
    var blocks by remember { mutableStateOf<List<PrivacyBlock>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    val appContext = remember(context) {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
        "Hermes for Android $version\nPackage: ${context.packageName}"
    }
    LaunchedEffect(context, retry) {
        failed = false
        try {
            blocks = withContext(Dispatchers.IO) {
                context.assets.open("privacy/privacy-policy.md").bufferedReader(Charsets.UTF_8).use {
                    privacyBlocks(it.readText()).also { content -> require(content.isNotEmpty()) }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
        }
    }
    Column(Modifier.fillMaxSize().semantics { paneTitle = "Privacy policy" }) {
        TopBar("Privacy policy")
        val content = blocks
        when {
            failed -> ErrorPanel("Couldn't read the packaged privacy policy.") { retry++ }
            content == null -> Skeleton(2)
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp)) {
                item {
                    Text("Bundled with this app · available offline. No account or pairing required.",
                        style = sans(14f, 500, P.tx2, 21f), modifier = Modifier.padding(bottom = 12.dp))
                    SelectionContainer {
                        Text(appContext, style = sans(13f, 400, P.tx2, 20f), modifier = Modifier.padding(bottom = 20.dp))
                    }
                }
                items(content.size) { index ->
                    val block = content[index]
                    SelectionContainer {
                        Text(block.text,
                            style = (when (block.level) {
                                0 -> sans(16f, 400, P.tx, 24f)
                                1 -> serif(28f, P.tx)
                                2 -> serif(24f, P.tx)
                                else -> sans(17f, 600, P.tx, 25f)
                            }).copy(textDirection = TextDirection.Content),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                                .then(if (block.level > 0) Modifier.semantics { heading() } else Modifier))
                    }
                }
            }
        }
    }
}

private data class LegalDocument(val file: String, val title: String, val source: String, val hash: String)
private data class LegalPage(val documents: List<LegalDocument>, val selected: LegalDocument?, val paragraphs: List<String>)

/** Only packaged UTF-8 assets: no browser, pairing credentials or network calls. */
@Composable
fun LicensesScreen(document: String? = null) {
    val context = LocalContext.current
    var page by remember(document) { mutableStateOf<LegalPage?>(null) }
    var failed by remember(document) { mutableStateOf(false) }
    var retry by remember(document) { mutableStateOf(0) }
    LaunchedEffect(document, retry) {
        failed = false
        try {
            page = withContext(Dispatchers.IO) {
                val inventory = context.assets.open("legal/inventory.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
                val entries = inventory.getJSONArray("documents")
                val documents = (0 until entries.length()).map { i ->
                    val entry = entries.getJSONObject(i)
                    LegalDocument(entry.getString("file"), entry.getString("title"), entry.optString("source_url"), entry.getString("sha256"))
                }
                // Resolve through the packaged catalog, never interpolate an arbitrary route into an asset path.
                val selected = document?.let { name -> documents.firstOrNull { it.file == name } ?: error("Unknown legal document") }
                val paragraphs = selected?.let {
                    require(it.file.matches(Regex("[A-Za-z0-9_.-]+")))
                    context.assets.open("legal/${it.file}").bufferedReader(Charsets.UTF_8).use { reader ->
                        // Bounded lazy items keep the full omnibus readable without one giant Text layout.
                        // No take(), ellipsis or maximum line count: all text is presented.
                        reader.readText().split("\n\n").flatMap { paragraph -> paragraph.chunked(4000) }
                    }
                }.orEmpty()
                LegalPage(documents, selected, paragraphs)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(if (document == null) "Licenses" else "License text", "Packaged on this phone · available offline")
        val current = page
        when {
            failed -> ErrorPanel("Couldn't read the packaged license text.") { retry++ }
            current == null -> Skeleton(2)
            current.selected == null -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                item {
                    Text("The microWakeWord Hey Jarvis weights and manifest have an explicit Apache-2.0 grant. Training provenance remains unresolved; this is not certification that all training inputs are permissive. Read the notice and model provenance before redistribution.",
                        style = sans(15f, 500, P.tx, 23f), modifier = Modifier.padding(20.dp))
                    Text("These notices are not an exhaustive dependency SBOM or legal clearance. Google ML Kit has separate terms.",
                        style = sans(14f, 400, P.tx2, 21f), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                items(current.documents, key = { it.file }) { entry ->
                    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .clickable(role = Role.Button) { Ui.push(Route.LegalR(entry.file)) }
                        .padding(horizontal = 20.dp, vertical = 14.dp)) {
                        Text(entry.title, style = sans(15f, 600, P.tx))
                        Text(entry.file, style = sans(12f, 400, P.mu))
                    }
                    Line()
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
                item {
                    SelectionContainer {
                        Column {
                            Text(current.selected.title, style = sans(19f, 600, P.tx), modifier = Modifier.padding(bottom = 12.dp))
                            if (current.selected.source.isNotBlank()) Text("Source: ${current.selected.source}", style = sans(13f, 400, P.tx2))
                            Text("SHA-256: ${current.selected.hash}", style = sans(12f, 400, P.tx2), modifier = Modifier.padding(vertical = 12.dp))
                        }
                    }
                }
                items(current.paragraphs.size) { i ->
                    SelectionContainer {
                        Text(current.paragraphs[i], style = sans(15f, 400, P.tx, 23f).copy(textDirection = TextDirection.Content),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp))
                    }
                }
            }
        }
    }
}
