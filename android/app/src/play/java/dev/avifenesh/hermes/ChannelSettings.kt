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
    SettingRow(stringResource(R.string.phone_settings_push),
        stringResource(if (push) R.string.phone_settings_push_on_gateway else R.string.phone_settings_push_off_gateway)) {}
    Line()
    SettingRow(stringResource(R.string.phone_settings_run_progress), stringResource(R.string.phone_settings_run_progress_sub, bidiIsolate(APP_NAME))) {}
    if (!push) {
        Line()
        SettingRow(stringResource(R.string.phone_settings_without_push), stringResource(R.string.phone_settings_without_push_sub, bidiIsolate(APP_NAME))) {}
    }
}
