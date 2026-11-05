package dev.avifenesh.hermes

import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.PixelCopy
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The room approval card as the hardware renderer draws it. A room first shows a member's approval from the room
 * summary (not ready, buttons disabled), then from the driver's detail (ready) in the same list slot. When Btn faded
 * through Modifier.alpha, that flip took a layer node out of the button's chain and the window kept drawing Approve
 * and Deny without their fills; a software draw of the same frame had them. So this reads the window with PixelCopy:
 * the card that turns ready must match, pixel for pixel, the card that was ready from its first frame.
 */
class RoomCardRenderTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    @Test fun aCardThatTurnsReadyDrawsLikeOneReadyFromTheStart() {
        val wrong = mutableListOf<String>()
        for (theme in listOf(1, 2)) {
            val act = ins.startActivitySync(Intent(ins.targetContext, RoomCardProbeActivity::class.java)
                .putExtra("theme", theme).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as RoomCardProbeActivity
            try {
                // The second card must reach the screen not ready first: taller, with its loading line.
                frames { act.bounds.size == 2 && act.bounds[1].height() > act.bounds[0].height() }
                window(act)
                ins.runOnMainSync { act.ready.value = true }
                frames { act.bounds[1].height() == act.bounds[0].height() }
                val shot = window(act)
                val (ready, turned) = act.bounds
                assertEquals("theme $theme: card sizes", ready.width() to ready.height(), turned.width() to turned.height())
                val w = ready.width(); val h = ready.height()
                val a = IntArray(w * h).also { shot.getPixels(it, 0, w, ready.left, ready.top, w, h) }
                val b = IntArray(w * h).also { shot.getPixels(it, 0, w, turned.left, turned.top, w, h) }
                val off = a.indices.count { i -> channels(a[i], b[i]) > 16 }
                // Blank buttons differ on about a fifth of the card; a correct frame differs on none.
                if (off * 1000 >= w * h) wrong += "theme $theme: $off of ${w * h} card pixels differ from the ready card"
            } finally {
                ins.runOnMainSync { act.finish() }
                ins.waitForIdleSync()
            }
        }
        assertTrue(wrong.joinToString("; "), wrong.isEmpty())
    }

    private fun channels(x: Int, y: Int) = maxOf(abs((x shr 16 and 255) - (y shr 16 and 255)),
        abs((x shr 8 and 255) - (y shr 8 and 255)), abs((x and 255) - (y and 255)))

    /** Waits frame by frame until [placed] holds, then four more frames so that layout is drawn and handed to the GPU. */
    private fun frames(placed: () -> Boolean) {
        var n = 0
        while (!placed()) { frame(); assertTrue("cards placed within 300 frames", ++n < 300) }
        repeat(4) { frame() }
    }

    private fun frame() {
        val done = CountDownLatch(1)
        ins.runOnMainSync { Choreographer.getInstance().postFrameCallback { done.countDown() } }
        assertTrue("frame callback", done.await(5, TimeUnit.SECONDS))
    }

    private fun window(act: RoomCardProbeActivity): Bitmap {
        val v = act.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = -1
        PixelCopy.request(act.window, bmp, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue("PixelCopy", done.await(5, TimeUnit.SECONDS))
        assertEquals("PixelCopy result", PixelCopy.SUCCESS, result)
        return bmp
    }
}
