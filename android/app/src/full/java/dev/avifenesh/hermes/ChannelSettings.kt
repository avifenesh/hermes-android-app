package dev.avifenesh.hermes

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
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
