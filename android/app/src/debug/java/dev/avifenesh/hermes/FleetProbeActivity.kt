package dev.avifenesh.hermes

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import org.json.JSONArray
import org.json.JSONObject

/** Shared production controls with intercepted callbacks. No terminal or network input. */
class FleetProbeActivity : FragmentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        check(!Prefs.paired && android.os.Build.PRODUCT.contains("sdk")) { "Disposable unpaired emulator only" }
        val theme = intent.getIntExtra("theme", 1)
        val mouse = intent.getBooleanExtra("mouse", true)
        val busy = intent.getBooleanExtra("busy", false)
        val legacy = intent.getBooleanExtra("legacy", false)
        val canStop = intent.getBooleanExtra("can_stop", true)
        val samples = listOf(
            "0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ",
            "╭──────────────────────────────╮",
            "│ Kitty choices                │",
            "│ 1. Continue   2. Cancel       │",
            "╰──────────────────────────────╯",
            "Unicode: café e\u0301 中文 עברית 🙂 👩🏽‍💻",
            "Progress: ⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏",
            "Symbols: → ← ↑ ↓ ✓ ✗ ┃ ━ ╋",
            "Wide: 界A  e\u0301B  👩🏽‍💻C",
        ) + (9 until 60).map { "Row $it " + "─".repeat(90) }
        setContent {
            HermesTheme(theme) {
                val imeVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
                var draft by remember { mutableStateOf(intent.getStringExtra("draft") ?: "") }
                var tick by remember { mutableIntStateOf(0) }
                val snapshot = remember(tick) {
                    JSONObject().put("available", !legacy).put("frame", "fixture-$tick")
                        .put("columns", 100).put("rows", samples.size).put("mouse", mouse && !legacy)
                        .put("lines", JSONArray(samples.dropLast(1) + "Refresh $tick"))
                }
                Column(Modifier.fillMaxSize().background(P.bg).imePadding()) {
                    TopBar("Kitty controls", if (imeVisible) null else "Disposable fixture · input intercepted", P.acc) {
                        LegacyAgentInterrupt(canStop, !legacy, busy) { Log.i("HermesFleetProbe", "STOP") }
                    }
                    if (!imeVisible) Text("Refresh fixture", style = sans(12f, 500, P.mu), modifier = Modifier
                        .clickable { tick++; Log.i("HermesFleetProbe", "REFRESH $tick") }.padding(8.dp))
                    TerminalViewport(snapshot, { row, col ->
                        Log.i("HermesFleetProbe", "CLICK " + JSONObject().put("row", row).put("col", col).put("frame", snapshot.str("frame")))
                    }, Modifier.weight(1f).fillMaxWidth())
                    Column(Modifier.fillMaxWidth().background(P.bg).navigationBarsPadding().padding(8.dp)) {
                        if (!imeVisible) FleetQuickReplies(busy) { Log.i("HermesFleetProbe", "QUICK $it") }
                        if (!legacy) TerminalControls({ body -> Log.i("HermesFleetProbe", "INPUT $body") }, busy, compact = imeVisible)
                        FleetComposer(draft, { draft = it }, {
                            Log.i("HermesFleetProbe", "SEND " + JSONObject().put("text", draft)); draft = ""
                        }, busy, compact = imeVisible)
                    }
                }
            }
        }
    }
}
