package dev.avifenesh.hermes

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

internal fun phoneSettingsTabs() = listOf("look" to str(R.string.channel_tab_look), "voice" to str(R.string.channel_tab_voice),
    "alerts" to str(R.string.channel_tab_alerts), "link" to str(R.string.channel_tab_connection), "control" to str(R.string.channel_tab_control))

internal fun LazyListScope.channelControlSettings() {
    item { Section(stringResource(R.string.channel_control_section), null) }
    item { PhoneControlPanel() }
}

@Composable
internal fun ChannelBatterySettings() {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(PowerManager::class.java)
    if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
        Line()
        SettingRow(stringResource(R.string.channel_battery_title), stringResource(R.string.channel_battery_body, APP_NAME)) {
            Btn(stringResource(R.string.common_allow), primary = false) { runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))) } }
        }
    }
}

/** Push status and the always-on live connection, a sideload-edition choice. */
@Composable
internal fun ChannelConnectionSettings(push: Boolean) {
    val ctx = LocalContext.current
    var live by remember { mutableStateOf(Prefs.live) }
    SettingRow("Push wake-ups", if (push) "On. Google wakes the phone, content comes straight from Hermes." else "Not set up on the server. The app falls back to its own connection.") {}
    Line()
    SettingRow("Stay connected in background", "Keeps a live connection and shows run progress. Uses a bit more battery.") {
        Toggle(live, { v -> live = v; Prefs.live = v; LiveService.sync(ctx); if (!v && !Hub.foreground && !Prefs.phoneArmed) Hub.disconnect() })
    }
}
