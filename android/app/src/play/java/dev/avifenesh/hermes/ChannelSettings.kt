package dev.avifenesh.hermes

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

internal fun phoneSettingsTabs() = listOf("look" to str(R.string.channel_tab_look), "voice" to str(R.string.channel_tab_voice),
    "alerts" to str(R.string.channel_tab_alerts), "link" to str(R.string.channel_tab_connection))

// A restored/stale route is an explicit unsupported state, never an arming surface.
internal fun LazyListScope.channelControlSettings() {
    item { Panel { SettingRow(stringResource(R.string.channel_control_unavailable_title), stringResource(R.string.channel_control_unavailable_body)) {} } }
}

@Composable
internal fun ChannelBatterySettings() {
    Line()
    SettingRow(stringResource(R.string.channel_background_title), stringResource(R.string.channel_background_body)) {}
}

/** Push status and what this edition's connection does. There is no always-on background connection here. */
@Composable
internal fun ChannelConnectionSettings(push: Boolean) {
    SettingRow("Push wake-ups", if (push) "On. Google wakes the phone; content comes straight from your gateway."
        else "Not set up on your gateway.") {}
    Line()
    SettingRow("Run progress", "After you send a message or approve a step from this phone, $APP_NAME stays connected while that run works and shows its progress in a notification. It stops when the run ends.") {}
    if (!push) {
        Line()
        SettingRow("Without push", "Notifications arrive while such a run is working and when you open $APP_NAME. " +
            "Android's periodic background check can also deliver them, often 15 minutes or more late. Set up push on your gateway for prompt notifications.") {}
    }
}
