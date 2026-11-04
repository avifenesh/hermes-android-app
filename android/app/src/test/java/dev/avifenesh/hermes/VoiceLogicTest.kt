package dev.avifenesh.hermes

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLogicTest {
    // ------------------------------------------------------------ peaks
    @Test fun peakPerWindowNormalizedToTheLoudest() {
        // 1 kHz, 10 ms windows = 10 frames. Raw peaks 0.125, 0.25 (a negative sample), 0.5, 0.0625.
        val m = PeakMeter(1000)
        val pcm = ShortArray(40)
        pcm[3] = 4096; pcm[12] = (-8192).toShort(); pcm[27] = 16384; pcm[39] = 2048
        m.addPcm16(pcm)
        assertEquals(40L, m.frames)
        assertArrayEquals(floatArrayOf(0.25f, 0.5f, 1f, 0.125f), m.finish(4), 1e-4f)
    }

    @Test fun interleavedChannelsTakeTheLouderSide() {
        val m = PeakMeter(1000)
        // Stereo frames: the right channel peaks in window 0, the left in window 1.
        val pcm = ShortArray(40)
        pcm[1] = 16384; pcm[2 * 15] = 8192
        m.addPcm16(pcm, channels = 2)
        assertEquals(20L, m.frames)
        assertArrayEquals(floatArrayOf(1f, 0.5f), m.finish(2), 1e-4f)
    }

    @Test fun floatPcmAndPartialLastWindowCount() {
        val m = PeakMeter(1000)
        val pcm = FloatArray(15); pcm[2] = -0.4f; pcm[14] = 0.8f
        m.addFloat(pcm)
        assertEquals(2, m.windowCount)
        assertArrayEquals(floatArrayOf(0.5f, 1f), m.finish(2), 1e-4f)
    }

    @Test fun longClipsStayWithinTheWindowBudgetAndKeepTheirPeaks() {
        val m = PeakMeter(1000, maxWindows = 8)
        // 100 windows of 10 frames: silence except one loud frame in window 70 and a softer one in window 5.
        val pcm = ShortArray(1000)
        pcm[705] = 30000; pcm[55] = 15000
        m.addPcm16(pcm)
        assertTrue(m.windowCount <= 8)
        assertEquals(160L, m.windowFrames)
        val wave = m.finish(8)!!
        assertEquals(1f, wave.max(), 1e-4f)
        // The loud frame at 705 ms of 1000 lands in bucket 5 of 8 (62.5% to 75%); the soft one at 55 ms in bucket 0.
        assertEquals(1f, wave[5], 1e-4f)
        assertEquals(0.5f, wave[0], 1e-3f)
    }

    @Test fun nothingDecodedIsNoWave() {
        assertNull(PeakMeter(48000).finish(64))
        val silent = PeakMeter(1000).apply { addPcm16(ShortArray(30)) }.finish(3)!!
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), silent, 0f)
    }

    @Test fun poolShrinksByMaxAndStretchesByNearest() {
        val src = floatArrayOf(0.1f, 0.9f, 0.2f, 0.3f, 0.8f, 0.4f)
        assertArrayEquals(floatArrayOf(0.9f, 0.8f), VoiceMath.pool(src, 2), 0f)
        assertArrayEquals(floatArrayOf(0.9f, 0.3f, 0.8f), VoiceMath.pool(src, 3), 0f)
        assertArrayEquals(floatArrayOf(0.1f, 0.1f, 0.9f, 0.9f), VoiceMath.pool(floatArrayOf(0.1f, 0.9f), 4), 0f)
        assertEquals(0, VoiceMath.pool(src, 0).size)
        assertArrayEquals(FloatArray(3), VoiceMath.pool(FloatArray(0), 3), 0f)
    }

    // ------------------------------------------------------------ time
    @Test fun clockReadsLikeAMediaPlayer() {
        assertEquals("0:00", VoiceMath.clock(0))
        assertEquals("0:00", VoiceMath.clock(999))
        assertEquals("0:59", VoiceMath.clock(59_999))
        assertEquals("1:00", VoiceMath.clock(60_000))
        assertEquals("12:34", VoiceMath.clock(754_000))
        assertEquals("59:59", VoiceMath.clock(3_599_999))
        assertEquals("1:00:00", VoiceMath.clock(3_600_000))
        assertEquals("0:00", VoiceMath.clock(-5))
        // Totals round, and a short clip never reads 0:00.
        assertEquals("0:03", VoiceMath.clock(2_600, total = true))
        assertEquals("0:02", VoiceMath.clock(2_400, total = true))
        assertEquals("0:01", VoiceMath.clock(120, total = true))
        assertEquals("0:00", VoiceMath.clock(0, total = true))
    }

    @Test fun spokenDurations() {
        assertEquals("0 seconds", VoiceMath.spoken(0))
        assertEquals("1 second", VoiceMath.spoken(1_200))
        assertEquals("45 seconds", VoiceMath.spoken(45_000))
        assertEquals("1 minute", VoiceMath.spoken(60_000))
        assertEquals("1 minute 5 seconds", VoiceMath.spoken(65_000))
        assertEquals("2 hours 3 minutes", VoiceMath.spoken(7_380_000))
    }

    // ------------------------------------------------------------ seeking
    @Test fun touchToFractionFollowsLayoutDirection() {
        assertEquals(0.25f, VoiceMath.fraction(50f, 200f, rtl = false), 0f)
        assertEquals(0.75f, VoiceMath.fraction(50f, 200f, rtl = true), 0f)
        assertEquals(0f, VoiceMath.fraction(-10f, 200f, rtl = false), 0f)
        assertEquals(1f, VoiceMath.fraction(260f, 200f, rtl = false), 0f)
        assertEquals(0f, VoiceMath.fraction(260f, 200f, rtl = true), 0f)
        assertEquals(0f, VoiceMath.fraction(10f, 0f, rtl = false), 0f)
    }

    @Test fun seekAndStepStayInsideTheClip() {
        assertEquals(15_000L, VoiceMath.seekMs(0.25f, 60_000))
        assertEquals(60_000L, VoiceMath.seekMs(1.4f, 60_000))
        assertEquals(0L, VoiceMath.seekMs(-0.2f, 60_000))
        assertEquals(0L, VoiceMath.seekMs(0.5f, 0))
        assertEquals(7_000L, VoiceMath.step(2_000, 60_000, 5_000))
        assertEquals(0L, VoiceMath.step(2_000, 60_000, -5_000))
        assertEquals(60_000L, VoiceMath.step(58_000, 60_000, 5_000))
    }

    @Test fun barsFillAsThePlayheadPassesTheirMiddle() {
        assertEquals(0, VoiceMath.playedBars(0f, 40))
        assertEquals(40, VoiceMath.playedBars(1f, 40))
        assertEquals(10, VoiceMath.playedBars(0.25f, 40))
        assertEquals(1, VoiceMath.playedBars(0.0125f, 40))
        assertEquals(0, VoiceMath.playedBars(0.0124f, 40))
        assertEquals(40, VoiceMath.playedBars(3f, 40))
        // 3 px bars, 2 px gaps: 10 bars span 48 px; 47 px fits 9.
        assertEquals(10, VoiceMath.barCount(48f, 3f, 2f))
        assertEquals(9, VoiceMath.barCount(47f, 3f, 2f))
        assertEquals(0, VoiceMath.barCount(2f, 3f, 2f))
    }

    @Test fun speedCyclesOneHalfTwo() {
        assertEquals(1.5f, VoiceMath.nextSpeed(1f), 0f)
        assertEquals(2f, VoiceMath.nextSpeed(1.5f), 0f)
        assertEquals(1f, VoiceMath.nextSpeed(2f), 0f)
        assertEquals(1f, VoiceMath.nextSpeed(1.25f), 0f) // an unknown speed resets to 1x
        assertEquals(listOf("1x", "1.5x", "2x"), VoiceMath.SPEEDS.map { VoiceMath.speedLabel(it) })
    }

    // ------------------------------------------------------------ cache
    @Test fun cacheIsBoundedByBytesAndEvictsTheLeastRecentlyUsed() {
        val w = { Wave(1000, FloatArray(8)) } // 48 + 32 = 80 bytes each
        val c = WaveCache(250)
        c.put("a", w()); c.put("b", w()); c.put("c", w())
        assertEquals(240, c.bytes)
        assertTrue(c.get("a") != null) // a is now the most recent
        c.put("d", w())
        assertNull(c.get("b"))
        assertTrue(c.get("a") != null && c.get("c") != null && c.get("d") != null)
        assertTrue(c.bytes <= 250)
        // Replacing a key frees its old size; an entry larger than the whole cache is never kept.
        c.put("a", Wave(1000, FloatArray(2)))
        assertEquals(56 + 160, c.bytes)
        c.put("huge", Wave(1000, FloatArray(100)))
        assertNull(c.get("huge"))
        assertEquals(3, c.size())
        // Failed decodes are cached too, so a broken clip is not decoded on every scroll.
        c.put("broken", Wave(0, null))
        assertTrue(c.get("broken") != null && c.get("broken")!!.peaks == null)
    }

    // ------------------------------------------------------------ chat runs
    private fun msg(id: String, role: String, kind: String = "text", ts: Double = 1000.0, meta: JSONObject = JSONObject()) =
        Msg(id, "c", role, kind, "x", meta, ts, 0)

    private val quiet = { m: Msg -> m.kind == "activity" || m.kind == "trace" || m.kind == "status" }
    private val day = { a: Msg, b: Msg -> (a.ts / 86_400).toLong() == (b.ts / 86_400).toLong() }

    @Test fun runsStartOnANewSpeakerACardOrANewDay() {
        val ms = listOf(
            msg("u1", "user"), msg("u2", "user", "voice"),
            msg("a1", "agent"), msg("t", "agent", "trace"), msg("n", "agent", "status"), msg("a2", "agent", "voice"),
            msg("ap", "agent", "approval"), msg("a3", "agent"),
            msg("a4", "agent", ts = 1000.0 + 86_400),
        )
        val leads = ms.indices.filter { leadsRun(ms, it, quiet, day) }.map { ms[it].id }
        // Traces and notices between a1 and a2 do not split the run; an approval card does; so does midnight.
        assertTrue("u1" in leads && "a1" in leads && "a3" in leads && "a4" in leads)
        assertFalse("u2" in leads || "a2" in leads)
    }

    @Test fun aTranscriptEchoHidesOnlyWhenItsVoiceMessageIsListed() {
        val voice = msg("v", "user")
        val echo = msg("e", "agent", "status", meta = JSONObject().put("quiet", true).put("transcript_of", "v"))
        val orphan = msg("o", "agent", "status", meta = JSONObject().put("transcript_of", "not-loaded"))
        val gone = msg("d", "agent", "deleted")
        assertEquals(listOf("v", "o"), chatVisible(listOf(voice, echo, orphan, gone)).map { it.id })
        assertEquals(listOf("e"), chatVisible(listOf(echo)).map { it.id })
    }
}
