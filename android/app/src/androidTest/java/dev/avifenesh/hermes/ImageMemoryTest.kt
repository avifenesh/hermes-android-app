package dev.avifenesh.hermes

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.junit.Assert.*
import org.junit.Test

/** Run on a disposable emulator; no pairing or live network is required. */
class ImageMemoryTest {
    @Test fun countsBackingAllocationAndDoesNotRecycleEvictedVisibleBitmap() {
        Img.cache.evictAll()
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val allocation = bitmap.allocationByteCount
        bitmap.reconfigure(32, 32, Bitmap.Config.ARGB_8888)
        val visible = bitmap.asImageBitmap()
        try {
            Img.cache.put("allocation-test", visible)
            assertEquals(allocation, Img.cache.size())
            assertTrue("Expected retained allocation larger than logical byte count", allocation > bitmap.byteCount)
            Img.trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            assertEquals(0, Img.cache.size())
            assertFalse("Eviction must not recycle a still-visible bitmap", bitmap.isRecycled)
            assertEquals(32, visible.width)
            assertEquals(0, bitmap.getPixel(0, 0))
        } finally { Img.cache.evictAll(); bitmap.recycle() }
    }

    @Test fun runningLowHalvesCacheAndCriticalDropsAllWithoutChangingBudget() {
        Img.cache.evictAll()
        val bitmaps = List(4) { Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888) }
        val budget = Img.cache.maxSize()
        assertEquals((Runtime.getRuntime().maxMemory() / 8).coerceIn(1, Int.MAX_VALUE.toLong()).toInt(), budget)
        try {
            bitmaps.forEachIndexed { index, bitmap -> Img.cache.put("trim-$index", bitmap.asImageBitmap()) }
            val before = Img.cache.size()
            Img.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            assertTrue(Img.cache.size() <= before / 2)
            Img.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
            assertEquals(0, Img.cache.size())
            assertEquals(budget, Img.cache.maxSize())
            bitmaps.forEach { assertFalse(it.isRecycled) }
        } finally { Img.cache.evictAll(); bitmaps.forEach { it.recycle() } }
    }
}
