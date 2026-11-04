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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
    Section(stringResource(R.string.machines_title), list?.size?.let { Fmt.int(it) } ?: stringResource(R.string.machines_unavailable))
    if (list == null) {
        ErrorPanel(stringResource(R.string.machines_read_failed)) {
            saved = runCatching { Machines.all() }
        }
        return
    }
    Panel {
        list.forEachIndexed { i, m ->
            if (i > 0) Line()
            val active = Machines.isActive(m)
            Row(Modifier.fillMaxWidth().clickable(enabled = !active) {
                scope.launch { runCatching { Machines.switchTo(ctx, m) }.onSuccess { saved = runCatching { Machines.all() }; Ui.say(R.string.machines_switched_to, m.str("server").ifBlank { str(R.string.machines_fallback_name) }) }.onFailure { Ui.say(R.string.machines_switch_failed) } }
            }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (active) LiveDot(9.dp) else Dot(p.s4, 9.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.str("server").ifBlank { Uri.parse(m.str("base")).host ?: stringResource(R.string.machines_fallback_name) }, style = sans(15f, 600, p.tx).content())
                    Text(bidiJoin(listOfNotNull(Uri.parse(m.str("base")).host.orEmpty(), m.str("profile").takeIf { it.isNotBlank() })),
                        style = mono(11.5f, 500, p.mu), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (active) Text(lat[m.str("base")]?.let { stringResource(R.string.machines_latency, it) } ?: stringResource(R.string.machines_active), style = mono(12f, 600, p.acct))
                else Text(stringResource(R.string.machines_forget), style = sans(13f, 600, p.mu), modifier = Modifier.clickable { forget = m }.padding(6.dp))
            }
        }
        Line()
        Row(Modifier.fillMaxWidth().clickable { Ui.pairLink.value = Uri.parse("hermes://pair") }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icn(Ic.plus, p.acct, 18.dp); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.machines_pair_another), style = sans(15f, 600, p.acct))
        }
    }
    forget?.let { m ->
        HDialog(stringResource(R.string.machines_forget_title, bidiIsolate(m.str("server").ifBlank { stringResource(R.string.machines_fallback_name) })), onDismiss = { forget = null }, body = {
                Text(stringResource(R.string.machines_forget_body), style = sans(14.5f, 400, p.tx2))
            }) {
                Btn(stringResource(R.string.common_cancel), primary = false) { forget = null }
                Btn(stringResource(R.string.machines_forget), danger = true) {
                    runCatching { Machines.forget(m.str("base")) }
                        .onSuccess { saved = runCatching { Machines.all() }; forget = null }
                        .onFailure { Ui.say(R.string.machines_update_failed) }
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
    SettingRow(stringResource(R.string.cron_delivery_title), when {
        sum == null -> stringResource(R.string.cron_delivery_loading)
        total == 0 -> stringResource(R.string.cron_delivery_none)
        elsewhere == 0 -> pluralStringResource(R.plurals.cron_delivery_all_to_app, toApp, toApp)
        else -> pluralStringResource(R.plurals.cron_delivery_some_elsewhere, total, toApp, total, elsewhere)
    }) { if (elsewhere > 0) Btn(stringResource(R.string.cron_delivery_move), primary = false) { ask = true } }
    if (ask) HDialog(stringResource(R.string.cron_delivery_dialog_title), onDismiss = { ask = false }, body = {
            Text(pluralStringResource(R.plurals.cron_delivery_dialog_body, elsewhere, elsewhere, bidiIsolate("Hermes Mobile")), style = sans(14.5f, 400, p.tx2))
        }) {
            Btn(stringResource(R.string.common_cancel), primary = false) { ask = false }
            Btn(stringResource(R.string.cron_delivery_move)) {
            ask = false
            scope.launch {
                runCatching { Api.post("/v1/cron-delivery", JSONObject().put("deliver", "mobile")) }
                    .onSuccess { sum = it; val moved = it.optJSONArray("moved")?.length() ?: 0; Ui.say(plural(R.plurals.cron_delivery_moved, moved, moved)) }.onFailure { Ui.say(it.message) }
            }
        }
        }
}
