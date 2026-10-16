package dev.avifenesh.hermes

import android.util.Base64
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/** Sign "git\n<repo>\n<action>\n<ts>" with the biometric-gated key; null when cancelled. */
suspend fun gitSignature(act: FragmentActivity, repo: String, action: String): Map<String, String>? = suspendCancellableCoroutine { k ->
    val ts = (System.currentTimeMillis() / 1000).toString()
    val payload = "git\n$repo\n$action\n$ts".toByteArray()
    val sig = runCatching { Keys.approvalSignature() }.getOrNull() ?: run { k.resume(null); return@suspendCancellableCoroutine }
    fun done(s: java.security.Signature?) {
        val out = s?.let { runCatching { it.update(payload); Base64.encodeToString(it.sign(), Base64.NO_WRAP) }.getOrNull() }
        k.resume(out?.let { mapOf("X-HM-Ts" to ts, "X-HM-ASig" to it) })
    }
    if (Prefs.noLock) { done(sig); return@suspendCancellableCoroutine }
    BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) = done(r.cryptoObject?.signature)
        override fun onAuthenticationError(code: Int, s: CharSequence) { if (k.isActive) k.resume(null) }
    }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(action.replace('-', ' ').replaceFirstChar { it.uppercase() } + " · $repo")
        .setSubtitle("Leaves this machine").setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL).build(), BiometricPrompt.CryptoObject(sig))
}

@Composable
fun ProjectsScreen() {
    val p = P
    var repos by remember { mutableStateOf<List<JSONObject>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var dirtyOnly by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(Ui.stack.size, retry) {
        err = null
        runCatching { repos = Api.get("/v1/projects").optJSONArray("repos").objs() }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; err = it.message ?: "Could not load projects." }
    }
    val list = repos.orEmpty().filter { !dirtyOnly || it.optInt("changed") > 0 || it.optInt("ahead") > 0 }
    Column(Modifier.fillMaxSize()) {
        TopBar("Projects", err ?: repos?.let { "${it.size} repos · most recent first" } ?: "Loading…")
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("All", !dirtyOnly) { dirtyOnly = false }
            Chip("Has changes", dirtyOnly) { dirtyOnly = true }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (err != null) item { ErrorPanel(err!!) { retry++ } }
            else if (repos == null) item { Skeleton() }
            else if (list.isEmpty()) item {
                Empty(if (dirtyOnly) "No projects with changes" else "No projects yet",
                    if (dirtyOnly) "Choose All to see clean repositories." else "Repositories on your machine will appear here.")
            }
            items(list, key = { it.str("id") }) { r ->
                Row(Modifier.fillMaxWidth().clickable { Ui.push(Route.RepoR(r.str("id"))) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.str("id"), style = sans(15.5f, 600, p.tx))
                        Text(r.str("branch").ifBlank { "detached" } + (if (r.optInt("ahead") > 0) " ↑${r.optInt("ahead")}" else "") + (if (r.optInt("behind") > 0) " ↓${r.optInt("behind")}" else ""),
                            style = mono(11.5f, 500, p.mu), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val ch = r.optInt("changed")
                    if (r.optInt("conflicted") > 0) Pill("conflict", p.bad, p.badd)
                    else if (ch > 0) Text("$ch · +${r.optInt("added")} −${r.optInt("removed")}", style = mono(12f, 600, p.att))
                    Spacer(Modifier.width(10.dp))
                    Text(rel(r.optDouble("touched", 0.0)), style = mono(11.5f, 500, p.mu))
                }
            }
        }
    }
}

@Composable
private fun RepoDiff(rid: String, path: String, revision: JSONObject?) {
    var retry by remember(rid, path, revision) { mutableStateOf(0) }
    var diff by remember(rid, path, revision, retry) { mutableStateOf<String?>(null) }
    var err by remember(rid, path, revision, retry) { mutableStateOf<String?>(null) }
    LaunchedEffect(rid, path, revision, retry) {
        try {
            diff = Api.get("/v1/projects/${Fleet.enc(rid)}/diff?file=${Fleet.enc(path)}").str("diff")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { err = e.message ?: "Could not load diff." }
    }
    val text = diff
    when {
        err != null -> ErrorPanel("Could not load diff: $err") { retry++ }
        text == null -> Skeleton(1)
        text.isBlank() -> Empty("No diff available", "This file has no text changes to show.")
        else -> DiffPane(text)
    }
}

@Composable
fun RepoScreen(rid: String) {
    val p = P
    val act = LocalAct.current
    val scope = rememberCoroutineScope()
    var info by remember(rid) { mutableStateOf<JSONObject?>(null) }
    var err by remember(rid) { mutableStateOf<String?>(null) }
    var openFile by remember(rid) { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    suspend fun load() { runCatching { info = Api.get("/v1/projects/${Fleet.enc(rid)}"); err = null }.onFailure { err = it.message } }
    LaunchedEffect(rid) { load() }
    fun run(action: String, body: JSONObject = JSONObject(), signed: Boolean = false) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val h = if (signed) gitSignature(act, rid, action) ?: run { busy = false; return@launch } else emptyMap()
                val o = Api.post("/v1/projects/${Fleet.enc(rid)}/$action", body, h)
                val res = o.optJSONObject("result")
                val url = res?.str("url").orEmpty()
                Ui.say(when (action) { "commit" -> "Committed"; "commit-push" -> "Committed and pushed"; "push" -> "Pushed"; "create-pr" -> if (url.isNotBlank()) "PR opened" else "Done"; else -> "Done" })
                if (action == "create-pr" && url.startsWith("https://")) runCatching { act.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                if (action.startsWith("commit")) msg = ""
                load()
            } catch (e: Exception) { Ui.say(e.message) } finally { busy = false }
        }
    }
    val st = info?.optJSONObject("status")
    val files = st?.optJSONArray("files").objs()
    val ship = info?.optJSONObject("ship")
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(rid, err ?: st?.let { (it.str("branch").ifBlank { "detached" }) + " · ${it.optInt("changed")} changed · +${it.optInt("added")} −${it.optInt("removed")}" } ?: "Loading…")
        LazyColumn(Modifier.weight(1f)) {
            if (st != null && (st.optInt("ahead") > 0 || st.optInt("behind") > 0)) item {
                Text("↑${st.optInt("ahead")} ahead · ↓${st.optInt("behind")} behind " + (st.str("defaultBranch").takeIf { it.isNotBlank() }?.let { "origin" } ?: ""),
                    style = mono(12.5f, 600, p.tx2), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            ship?.optJSONObject("pr")?.let { pr -> item {
                Text("PR #${pr.optInt("number")} · ${pr.str("state").lowercase()} · ${pr.str("title")}", style = sans(13.5f, 600, p.acct),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).clickable {
                        runCatching { act.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(pr.str("url")))) } })
            } }
            item { Section("Changes", files.size.toString()) }
            if (files.isEmpty() && st != null) item { Text("Working tree clean.", style = sans(14f, 400, p.mu), modifier = Modifier.padding(horizontal = 20.dp)) }
            items(files, key = { it.str("path") }) { f ->
                val path = f.str("path")
                Column {
                    Row(Modifier.fillMaxWidth().clickable { openFile = if (openFile == path) null else path }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(when { f.optBoolean("conflicted") -> "!"; f.optBoolean("untracked") -> "?"; f.optBoolean("staged") -> "●"; else -> "○" },
                            style = mono(13f, 700, when { f.optBoolean("conflicted") -> p.bad; f.optBoolean("staged") -> p.good; else -> p.att }))
                        Spacer(Modifier.width(10.dp))
                        Text(path, style = mono(12.5f, 500, p.tx), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(if (f.optBoolean("staged")) "unstage" else "stage", style = sans(12.5f, 600, p.acct),
                            modifier = Modifier.clickable { run(if (f.optBoolean("staged")) "unstage" else "stage", JSONObject().put("file", path)) }.padding(6.dp))
                    }
                    if (openFile == path) Column(Modifier.padding(horizontal = 12.dp)) { RepoDiff(rid, path, info) }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
        if (files.isNotEmpty() || (st?.optInt("ahead") ?: 0) > 0) Column(Modifier.fillMaxWidth().background(p.bg).padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (files.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Stage all", false) { run("stage") }
                    Chip("Ask ${agentName()} for a message", false) {
                        scope.launch {
                            runCatching {
                                val cid = Hub.newChat("Commit message · $rid")
                                Hub.send(cid, "In ~/projects/$rid, read the staged diff (or the whole working tree if nothing is staged) and reply with ONLY a commit message: subject ≤ 60 chars, blank line, short body.")
                                Ui.open(cid)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    InputBox(msg, { msg = it }, "Commit message", Modifier.weight(1f), maxLines = 4)
                    Spacer(Modifier.width(8.dp))
                    Btn(if (busy) "…" else "Commit", enabled = !busy && msg.isNotBlank()) { run("commit", JSONObject().put("message", msg.trim())) }
                }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if ((st?.optInt("ahead") ?: 0) > 0) Btn("Push", Modifier.weight(1f), primary = false, enabled = !busy) { run("push", signed = true) }
                if (ship?.optJSONObject("pr") == null && st?.str("branch") != st?.str("defaultBranch")) Btn("Open PR", Modifier.weight(1f), primary = false, enabled = !busy) { run("create-pr", signed = true) }
            }
        }
    }
}
