package dev.avifenesh.hermes

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable

internal fun phoneSettingsTabs() = listOf("look" to "Look", "voice" to "Voice", "alerts" to "Alerts", "link" to "Connection")

// A restored/stale route is an explicit unsupported state, never an arming surface.
internal fun LazyListScope.channelControlSettings() {
    item { Panel { SettingRow("Unavailable in this edition", "This Play preview cannot read or operate other apps. Remote commands cannot enable this capability.") {} } }
}

@Composable
internal fun ChannelBatterySettings() {
    Line()
    SettingRow("Background delivery", "Android battery optimization stays enabled. Push (when configured) and periodic sync remain available; background delivery can be delayed.") {}
}
