package dev.avifenesh.hermes

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val URL = Regex("https?://[^\\s)\\]>\"']+")
private val PR = Regex("github\\.com/([^/\\s]+/[^/\\s]+)/(pull|issues)/(\\d+)")

/** Links in a digest worth one tap: PRs/issues first, then other URLs (deduped, max 4). */
fun digestLinks(text: String): List<Pair<String, String>> {
    val urls = URL.findAll(text).map { it.value.trimEnd('.', ',', ';', ':') }.distinct().toList()
    val pr = urls.mapNotNull { u -> PR.find(u)?.let { m ->
        val repo = m.groupValues[1].substringAfter('/')
        (if (m.groupValues[2] == "pull") str(R.string.digest_link_pr, repo, m.groupValues[3]) else repo + " #" + m.groupValues[3]) to u
    } }
    val other = urls.filter { u -> pr.none { it.second == u } }.map { u -> (Uri.parse(u).host?.removePrefix("www.") ?: str(R.string.digest_link_fallback)) to u }
    return (pr + other).take(4)
}

/** Action chips under a cron/digest thread: follow-ups, job control, and the links the report mentions. */
@Composable
fun DigestActions(cid: String, last: Msg?, send: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val jid = cid.removePrefix("cron-job-").takeIf { cid.startsWith("cron-job-") }
    val links = last?.let { digestLinks(it.content) }.orEmpty()
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 2.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, u) in links) Chip("↗ " + label, false) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        Chip(stringResource(R.string.digest_tldr), false) { send("TL;DR of the latest report in 3 bullets, what (if anything) needs me.") }
        Chip(stringResource(R.string.digest_dig_deeper), false) { send("Dig deeper into the most important item in the latest report. Verify against current sources.") }
        Chip(stringResource(R.string.digest_act_on_it), false) { send("Do the next concrete action the latest report implies. Ask only if it's destructive.") }
        if (jid != null) {
            Chip(stringResource(R.string.digest_run_again), false) { scope.launch { runCatching { Api.post("/v1/cron/$jid/run"); Ui.say(R.string.digest_queued) }.onFailure { Ui.say(it.message) } } }
            Chip(stringResource(R.string.digest_pause_job), false) { scope.launch { runCatching { Api.post("/v1/cron/$jid/pause"); Ui.say(R.string.digest_paused) }.onFailure { Ui.say(it.message) } } }
            Chip(stringResource(R.string.digest_job), false) { Ui.push(Route.JobR(jid)) }
        }
    }
}
