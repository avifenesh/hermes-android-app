package dev.avifenesh.hermes

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity

/** Debug-only host for WakeWordPanelTest: the production Me > This phone > Voice wake-word panel on its own. */
class WakeWordPanelProbeActivity : FragmentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent {
            HermesTheme(1) {
                Box(Modifier.fillMaxSize().background(P.bg).systemBarsPadding().padding(horizontal = 14.dp)) { WakeWordPanel() }
            }
        }
    }
}
