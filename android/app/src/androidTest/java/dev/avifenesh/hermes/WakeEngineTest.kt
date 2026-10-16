package dev.avifenesh.hermes

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random
import io.homeassistant.companion.android.microwakeword.MicroWakeWord as ReferenceWakeWord

/** Invoke only on an isolated test installation. All audio stays local. */
class WakeEngineTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val assets get() = instrumentation.targetContext.assets
    private fun model() = assets.open("microwakeword/hey_jarvis.tflite").use { it.readBytes() }
    private fun fixture(): ShortArray {
        val random = Random(618L)
        return ShortArray(WakeWord.RATE * 12 + 79) { i ->
            when {
                i < WakeWord.RATE * 4 -> 0
                i < WakeWord.RATE * 8 -> (random.nextInt(8192) - 4096).toShort()
                else -> if (i % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE
            }
        }
    }
    private fun events(ww: WakeWord, pcm: ShortArray, chunks: IntArray): List<Long> {
        var position = 0
        var chunkIndex = 0
        val hits = mutableListOf<Long>()
        while (position < pcm.size) {
            val end = minOf(position + chunks[chunkIndex++ % chunks.size], pcm.size)
            ww.feed(pcm.copyOfRange(position, end), onDetection = { hits.add(it) })
            position = end
        }
        return hits
    }
    private fun reference(pcm: ShortArray, cutoff: Float): List<Long> {
        val hits = mutableListOf<Long>()
        ReferenceWakeWord(model(), cutoff).use { ref ->
            var position = 0
            while (position + WakeWord.STEP <= pcm.size) {
                if (ref.feed(pcm.copyOfRange(position, position + WakeWord.STEP))) hits.add((position + WakeWord.STEP).toLong())
                position += WakeWord.STEP
            }
        }
        return hits
    }

    @Test fun testReferenceParity() {
        val pcm = fixture()
        // 0.97 is the shipping recommendation. 0.1 is a test-only stress cutoff, not a preset.
        for (cutoff in listOf(0.97f, 0.1f)) {
            val expected = reference(pcm, cutoff)
            WakeWord.load(assets, cutoff).use { ww ->
                assertEquals(expected, events(ww, pcm, intArrayOf(1280)))
                assertTrue("real inference ran", ww.snapshot()[0] > 0)
            }
        }
    }

    @Test fun testChunkPartitionsAndColdRecreation() {
        val pcm = fixture()
        WakeWord.load(assets).use { ww ->
            val baseline = events(ww, pcm, intArrayOf(160))
            val baselineState = ww.snapshot()
            assertTrue(baselineState[0] > 0)
            for (chunks in listOf(intArrayOf(1, 159, 17, 1280, 3, 4096), intArrayOf(1280), intArrayOf(4096))) {
                ww.reset()
                assertEquals("fresh interpreter", 0, ww.snapshot()[0])
                assertEquals(baseline, events(ww, pcm, chunks))
                assertTrue(baselineState.contentEquals(ww.snapshot()))
            }
            // Dirty resource variables and pending PCM, then reset: compare every native output
            // and event against a new cold engine, not just the final Boolean.
            ww.feed(pcm.copyOfRange(0, 3217))
            ww.reset()
            WakeWord.load(assets).use { cold ->
                for (i in 0 until pcm.size / 160) {
                    val step = pcm.copyOfRange(i * 160, (i + 1) * 160)
                    assertEquals(cold.feed(step), ww.feed(step))
                    assertTrue("state at step $i", cold.snapshot().contentEquals(ww.snapshot()))
                }
            }
        }
    }

    @Test fun testSilenceAndClosedEngine() {
        val ww = WakeWord.load(assets)
        ww.use {
            assertTrue(events(it, ShortArray(16000 * 8), intArrayOf(1280)).isEmpty())
            assertTrue(it.snapshot()[0] > 0)
        }
        ww.close()
        try { ww.feed(ShortArray(160)); fail("closed engine accepted PCM") } catch (_: IllegalStateException) { }
        try { WakeWord(ByteArray(64)).close(); fail("invalid model accepted") } catch (_: IllegalStateException) { }
    }

    @Test fun testLegacyPreferenceMigration() {
        val sp = instrumentation.targetContext.getSharedPreferences("wake-migration-test", 0)
        try {
            for (optIn in listOf(false, true)) {
                sp.edit().clear().putBoolean("wakeword", optIn).putString("wakephrase", "hey_jarvis_v0.1")
                    .putFloat("wakethr", 0.3f).putString("unrelated", "preserve").commit()
                WakePreferences.migrate(sp)
                assertEquals(optIn, sp.getBoolean("wakeword", !optIn))
                assertEquals(WakeWord.MODEL_ID, sp.getString("wakephrase", null))
                assertEquals("preserve", sp.getString("unrelated", null))
                assertFalse(sp.contains("wakethr"))
                assertEquals(0.97f, WakePreferences.cutoff(sp), 0f)
                sp.edit().putFloat(WakePreferences.CUTOFF_KEY, 0.99f).commit()
                WakePreferences.migrate(sp)
                assertEquals(0.99f, WakePreferences.cutoff(sp), 0f)
                sp.edit().putFloat(WakePreferences.CUTOFF_KEY, Float.NaN).commit()
                assertEquals(0.97f, WakePreferences.cutoff(sp), 0f)
            }
        } finally { sp.edit().clear().commit() }
    }

    @Test fun testConfiguredWavFixture() {
        val path = InstrumentationRegistry.getArguments().getString("wakeFixture")
        if (path == null) {
            android.util.Log.i("HermesWakeFixture", "SKIPPED spoken quality fixture: no wakeFixture argument")
            assumeTrue("Spoken fixture not supplied; this is not a wake-quality pass", false)
        }
        val expectedMinimum = requireNotNull(InstrumentationRegistry.getArguments().getString("wakeMinHits")) {
            "Specify wakeMinHits explicitly (zero for negatives)"
        }.toInt()
        require(expectedMinimum >= 0)
        val pcm = WakePcm.wav(File(requireNotNull(path)).readBytes())
        val expected = reference(pcm, WakeWord.DEFAULT_CUTOFF)
        WakeWord.load(assets).use { ww ->
            val actual = events(ww, pcm, intArrayOf(1, 1279, 4096, 17))
            assertEquals(expected, actual)
            if (expectedMinimum == 0) assertTrue("negative fixture activated", actual.isEmpty())
            else assertTrue("positive fixture missed: $actual", actual.size >= expectedMinimum)
            android.util.Log.i("HermesWakeFixture", "PASS samples=${pcm.size} detectionSamples=$actual")
        }
    }
}
