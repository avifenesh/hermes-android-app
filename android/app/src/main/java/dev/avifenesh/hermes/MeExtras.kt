package dev.avifenesh.hermes

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun MachinesSection() {
    val p = P
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(runCatching { Machines.all() }) }
    val list = saved.getOrNull()
    var lat by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var forget by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) {
        val t0 = System.currentTimeMillis()
        runCatching { Hub.health() }.onSuccess { lat = lat + ((Prefs.base ?: "") to (System.currentTimeMillis() - t0)) }
    }
    Section("Machines", list?.size?.toString() ?: "unavailable")
    if (list == null) {
        ErrorPanel("Saved machines couldn't be read. Existing pairings have not been changed.") {
            saved = runCatching { Machines.all() }
        }
        return
    }
    Panel {
        list.forEachIndexed { i, m ->
            if (i > 0) Line()
            val active = Machines.isActive(m)
            Row(Modifier.fillMaxWidth().clickable(enabled = !active) {
                scope.launch { runCatching { Machines.switchTo(ctx, m) }.onSuccess { saved = runCatching { Machines.all() }; Ui.say("Switched to " + m.str("server").ifBlank { "machine" }) }.onFailure { Ui.say("Couldn't switch machines. Existing pairings have been kept.") } }
            }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (active) LiveDot(9.dp) else Dot(p.s4, 9.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.str("server").ifBlank { Uri.parse(m.str("base")).host ?: "machine" }, style = sans(15f, 600, p.tx))
                    Text(Uri.parse(m.str("base")).host.orEmpty() + (m.str("profile").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        style = mono(11.5f, 500, p.mu), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (active) Text(lat[m.str("base")]?.let { "$it ms" } ?: "active", style = mono(12f, 600, p.acct))
                else Text("Forget", style = sans(13f, 600, p.mu), modifier = Modifier.clickable { forget = m }.padding(6.dp))
            }
        }
        Line()
        Row(Modifier.fillMaxWidth().clickable { Ui.pairLink.value = Uri.parse("hermes://pair") }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icn(Ic.plus, p.acct, 18.dp); Spacer(Modifier.width(12.dp)); Text("Pair another machine", style = sans(15f, 600, p.acct))
        }
    }
    forget?.let { m ->
        HDialog("Forget " + m.str("server").ifBlank { "machine" } + "?", onDismiss = { forget = null }, body = {
                Text("The phone drops its token for that machine. Revoke the device there too (Me → Devices on that machine).", style = sans(14.5f, 400, p.tx2))
            }) {
                Btn("Cancel", primary = false) { forget = null }
                Btn("Forget", danger = true) {
                    runCatching { Machines.forget(m.str("base")) }
                        .onSuccess { saved = runCatching { Machines.all() }; forget = null }
                        .onFailure { Ui.say("Couldn't update saved machines. Existing pairings have been kept.") }
                }
            }
    }
}

@Composable
fun CronDeliveryRow() {
    val p = P
    val scope = rememberCoroutineScope()
    var sum by remember { mutableStateOf<JSONObject?>(null) }
    var ask by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { sum = Api.get("/v1/cron-delivery") } }
    val counts = sum?.optJSONObject("counts")
    val toApp = counts?.optInt("mobile") ?: 0
    val total = sum?.optInt("total") ?: 0
    val elsewhere = counts?.keys()?.asSequence()?.filter { it != "mobile" && it != "local" }?.sumOf { counts.optInt(it) } ?: 0
    SettingRow("Cron delivery", when {
        sum == null -> "Where scheduled jobs send their results"
        total == 0 -> "No cron jobs yet. New ones deliver to the app."
        elsewhere == 0 -> "All $toApp notifying jobs deliver to the app"
        else -> "$toApp of $total jobs deliver to the app; $elsewhere still go to Telegram or elsewhere"
    }) { if (elsewhere > 0) Btn("Move", primary = false) { ask = true } }
    if (ask) HDialog("Deliver cron to the app?", onDismiss = { ask = false }, body = {
            Text("$elsewhere jobs switch their delivery to Hermes Mobile. Local-only jobs stay local. You can change any job back in its editor.", style = sans(14.5f, 400, p.tx2))
        }) {
            Btn("Cancel", primary = false) { ask = false }
            Btn("Move") {
            ask = false
            scope.launch {
                runCatching { Api.post("/v1/cron-delivery", JSONObject().put("deliver", "mobile")) }
                    .onSuccess { sum = it; Ui.say("Moved ${it.optJSONArray("moved")?.length() ?: 0} jobs") }.onFailure { Ui.say(it.message) }
            }
        }
        }
}
