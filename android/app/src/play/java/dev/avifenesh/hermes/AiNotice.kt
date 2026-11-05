package dev.avifenesh.hermes

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

/** Play safeguard for AI-generated content: where replies come from, who sees chats, and how to report a reply. */
internal object AiNotice {
    /** The notice in the app language. The agent software and the publisher arrive as names, never translated, each
     * isolated so a sentence that starts with one still reads in the app's direction. */
    fun points(): List<String> = listOf(
        str(R.string.ai_notice_source, bidiIsolate(HERMES), bidiIsolate(EDITION_PUBLISHER)),
        str(R.string.ai_notice_risk),
        str(R.string.ai_notice_privacy, bidiIsolate(EDITION_PUBLISHER)),
        str(R.string.ai_notice_report),
    )
    // Acknowledged on this phone only: app-private preferences, never sent anywhere (backup is off). Written before
    // the dialog closes, so a kill right after "Got it" does not show the notice again.
    private const val PREFS = "edition"
    private const val SEEN = "ai_notice_seen"

    fun shouldShow(paired: Boolean, seen: Boolean): Boolean = paired && !seen
    fun seen(c: Context): Boolean = seen(prefs(c))
    fun acknowledge(c: Context) = acknowledge(prefs(c))
    fun seen(prefs: SharedPreferences): Boolean = prefs.getBoolean(SEEN, false)
    fun acknowledge(prefs: SharedPreferences) = prefs.edit(commit = true) { putBoolean(SEEN, true) }
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

@Composable
private fun AiNoticeDialog(close: String, onClose: () -> Unit) {
    val p = P
    HDialog(stringResource(R.string.ai_notice_title), onDismiss = onClose, body = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (point in AiNotice.points()) Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(p.acct))
                Spacer(Modifier.width(12.dp))
                Text(point, style = sans(14.5f, 400, p.tx2, 21f))
            }
        }
    }) { Btn(close) { onClose() } }
}

/** Once, before the first chat after pairing. Closing it in any way records the acknowledgement locally. */
@Composable
internal fun EditionFirstRunNotice() {
    val ctx = LocalContext.current
    var show by rememberSaveable { mutableStateOf(AiNotice.shouldShow(Prefs.paired, AiNotice.seen(ctx))) }
    if (show) AiNoticeDialog(stringResource(R.string.ai_notice_got_it)) { AiNotice.acknowledge(ctx); show = false }
}

/** Me > help rows: the notice stays reachable next to Report a problem. */
internal fun LazyListScope.editionHelpItems() {
    item {
        var open by remember { mutableStateOf(false) }
        Panel { NavRow(Ic.cpu, stringResource(R.string.ai_notice_title), stringResource(R.string.ai_notice_row_sub)) { open = true } }
        if (open) AiNoticeDialog(stringResource(R.string.common_close)) { open = false }
    }
}
