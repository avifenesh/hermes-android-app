package dev.avifenesh.hermes

import android.content.Context
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
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

/** Play safeguard for AI-generated content: where replies come from, who sees chats, and how to report a reply. */
internal object AiNotice {
    const val TITLE = "About AI replies"
    val POINTS: List<String> = listOf(
        "Replies come from the AI models and providers configured on your own Hermes gateway. $EDITION_PUBLISHER does not run or choose them.",
        "Replies can be wrong, outdated or inappropriate. Check anything important before you rely on it or let the agent act on it.",
        "$EDITION_PUBLISHER does not see your chats. They go between this phone, your gateway and the providers it uses.",
        "To report a reply, long-press it and tap Report. For anything else, open Me, then Report a problem. Nothing is sent until you review the report and tap Send report.",
    )
    // Acknowledged on this phone only: app-private preferences, never sent anywhere (backup is off).
    private const val PREFS = "edition"
    private const val SEEN = "ai_notice_seen"

    fun shouldShow(paired: Boolean, seen: Boolean): Boolean = paired && !seen
    fun seen(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SEEN, false)
    fun acknowledge(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(SEEN, true) }
}

@Composable
private fun AiNoticeDialog(close: String, onClose: () -> Unit) {
    val p = P
    HDialog(AiNotice.TITLE, onDismiss = onClose, body = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (point in AiNotice.POINTS) Row(verticalAlignment = Alignment.Top) {
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
    if (show) AiNoticeDialog("Got it") { AiNotice.acknowledge(ctx); show = false }
}

/** Me > help rows: the notice stays reachable next to Report a problem. */
internal fun LazyListScope.editionHelpItems() {
    item {
        var open by remember { mutableStateOf(false) }
        Panel { NavRow(Ic.cpu, AiNotice.TITLE, "Where replies come from, who sees your chats, how to report one") { open = true } }
        if (open) AiNoticeDialog("Close") { open = false }
    }
}
