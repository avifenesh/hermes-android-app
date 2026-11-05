package dev.avifenesh.hermes

import android.graphics.Rect
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlin.math.roundToInt

/** Debug-only host for RoomCardRenderTest: the same approval card twice, on the production composable. The top card
 *  is ready from its first frame. The bottom one turns ready when the test sets [ready], as a room's card does when
 *  the driver's detail replaces the room summary in the same list slot. Both report their window bounds. */
class RoomCardProbeActivity : FragmentActivity() {
    val ready = mutableStateOf(false)
    /** Window pixel bounds of the ready card and of the card that turns ready, once both are placed. */
    @Volatile var bounds: List<Rect> = emptyList()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val action = RoomAction("approval", "critic", "dtask:1", 2, "req-9", "git log --stat -n 20 -- benchmarks/", "Read the benchmark history")
        val members = listOf(RoomMember("critic", "critic", "critic", "Critic"))
        val placed = arrayOfNulls<Rect>(2)
        setContent {
            HermesTheme(intent.getIntExtra("theme", 1)) {
                Box(Modifier.fillMaxSize().background(P.bg)) {
                    Column(Modifier.systemBarsPadding().padding(horizontal = 14.dp)) {
                        for (i in 0..1) Box(Modifier.onGloballyPositioned { c ->
                            val at = c.positionInWindow()
                            val x = at.x.roundToInt(); val y = at.y.roundToInt()
                            placed[i] = Rect(x, y, x + c.size.width, y + c.size.height)
                            if (placed.all { it != null }) bounds = placed.map { Rect(it) }
                        }) { RoomActionCard(action, members, false, { _, _ -> }, i == 0 || ready.value) }
                    }
                }
            }
        }
    }
}
