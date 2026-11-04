package dev.avifenesh.hermes

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable

internal fun phoneSettingsTabs() = listOf("look" to "Look", "voice" to "Voice", "alerts" to "Alerts", "link" to "Connection")

// A restored/stale route is an explicit unsupported state, never an arming surface.
internal fun LazyListScope.channelControlSettings() {
    item { Panel { SettingRow("Unavailable in this edition", "This Play edition cannot read or operate other apps. Remote commands cannot enable this capability.") {} } }
}

@Composable
internal fun ChannelBatterySettings() {
    Line()
    SettingRow("Background delivery", "Android battery optimization stays enabled. Push (when configured) and periodic sync remain available; background delivery can be delayed.") {}
}

/** Push status and what this edition's connection does. There is no always-on background connection here. */
@Composable
internal fun ChannelConnectionSettings(push: Boolean) {
    SettingRow("Push wake-ups", if (push) "On. Google wakes the phone; content comes straight from your gateway."
        else "Not set up on your gateway.") {}
    Line()
    SettingRow("Run progress", "While a run you started from this phone is working, $APP_NAME stays connected and shows its progress in a notification. It stops when the run ends.") {}
    if (!push) {
        Line()
        SettingRow("Without push", "Notifications arrive while a run you started from this phone is working and when you open $APP_NAME. " +
            "Android's periodic background check can also deliver them, often 15 minutes or more late. Set up push on your gateway for prompt notifications.") {}
    }
}
