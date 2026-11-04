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

internal fun phoneSettingsTabs() = listOf("look" to "Look", "voice" to "Voice", "alerts" to "Alerts", "link" to "Connection", "control" to "Control")

internal fun LazyListScope.channelControlSettings() {
    item { Section("Agent phone control", null) }
    item { PhoneControlPanel() }
}

@Composable
internal fun ChannelBatterySettings() {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(PowerManager::class.java)
    if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
        Line()
        SettingRow("Battery optimization", "Some phones kill the background connection. Allow Hermes to run unrestricted.") {
            Btn("Allow", primary = false) { runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))) } }
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
