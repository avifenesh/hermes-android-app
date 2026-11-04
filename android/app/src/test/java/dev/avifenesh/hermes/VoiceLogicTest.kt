package dev.avifenesh.hermes

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.async

class VoiceLogicTest {
    @Before fun englishStrings() = TestStrings.install()

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

    private val day = { a: Msg, b: Msg -> (a.ts / 86_400).toLong() == (b.ts / 86_400).toLong() }

    private fun said(id: String, role: String, content: String, kind: String = "text") =
        Msg(id, "c", role, kind, content, JSONObject(), 1000.0, 0)

    @Test fun theChatsQuietRowsAreTracesActivityAndNotices() {
        assertTrue(quietInRun(msg("t", "agent", "trace")))
        assertTrue(quietInRun(msg("a", "agent", "activity")))
        assertTrue(quietInRun(msg("s", "agent", "status")))
        assertTrue(quietInRun(said("n", "agent", "\u23f3 Working on it")))
        assertTrue(quietInRun(said("b", "agent", "\ud83d\udcbb ls\n\ud83d\udcbb cat x")))
        assertFalse(quietInRun(said("r", "agent", "Here is the answer.")))
        assertFalse(quietInRun(said("u", "user", "\u23f3 typed by the user")))
        assertFalse(quietInRun(msg("ap", "agent", "approval")))
    }

    @Test fun runsStartOnANewSpeakerACardOrANewDay() {
        val ms = listOf(
            msg("u1", "user"), msg("u2", "user", "voice"), said("n", "agent", "\u23f3 Still working"),
            msg("a1", "agent"), msg("t", "agent", "trace"), msg("a2", "agent", "voice"),
            msg("ap", "agent", "approval"), msg("a3", "agent"),
            msg("a4", "agent", ts = 1000.0 + 86_400),
        )
        val leads = ms.indices.filter { leadsRun(ms, it, ::quietInRun, day) }.map { ms[it].id }
        // A notice before a1 does not make it a continuation of anything; a trace between a1 and a2 does not
        // split the run; an approval card does; so does midnight.
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

    // ------------------------------------------------------------ inbox preview
    @Test fun theTranscriptEchoIsNeverThePreviewAndAVoiceNotePreviewsAsItsWords() {
        val voice = Msg("v", "c", "user", "text", "", JSONObject().put("attachments", org.json.JSONArray()
            .put(JSONObject().put("kind", "voice").put("transcript", "pick up the parcel\nthen call"))), 1000.0, 0)
        val bare = Msg("b", "c", "user", "text", "", JSONObject().put("attachments", org.json.JSONArray()
            .put(JSONObject().put("mime", "audio/ogg"))), 1000.0, 0)
        val echo = msg("e", "agent", "status", meta = JSONObject().put("quiet", true).put("transcript_of", "v"))
        assertTrue(isTranscriptEcho(echo))
        assertFalse(previewCandidate(echo))
        assertTrue(previewCandidate(voice) && previewCandidate(said("r", "agent", "\ud83c\udf99\ufe0f \"plain echo bubble\"")))
        assertFalse(previewCandidate(msg("t", "agent", "trace")) || previewCandidate(msg("d", "agent", "deleted")))
        assertEquals("pick up the parcel", chatPreview(voice))
        assertEquals("Voice message", chatPreview(bare))
        assertEquals("Caption wins", chatPreview(Msg("c2", "c", "user", "text", "Caption wins", voice.meta, 1000.0, 0)))
        assertEquals("Voice message", chatPreview(msg("k", "agent", "voice")))
    }

    // ------------------------------------------------------------ fetching
    @Test fun onlySmallVoiceNotesOnUnmeteredNetworksDownloadOnTheirOwn() {
        val cap = VoiceFetchRules.AUTO_BYTES
        assertTrue(VoiceFetchRules.auto(voiceKind = true, size = 240_000, metered = false, failedBefore = false))
        assertTrue(VoiceFetchRules.auto(true, cap, false, false))
        assertFalse(VoiceFetchRules.auto(true, cap + 1, false, false))
        assertFalse(VoiceFetchRules.auto(true, null, false, false))
        assertFalse(VoiceFetchRules.auto(true, 240_000, metered = true, failedBefore = false))
        assertFalse(VoiceFetchRules.auto(true, 240_000, false, failedBefore = true))
        assertFalse(VoiceFetchRules.auto(voiceKind = false, size = 240_000, metered = false, failedBefore = false))
    }

    @Test fun aTapDuringAnAutomaticDownloadWaitsForItInsteadOfRestartingIt() {
        assertEquals(FetchTap(VoiceFetch.WAITING, startDownload = false), VoiceTaps.tap(VoiceFetch.FETCHING, tapStarted = false))
        assertTrue(VoiceTaps.landed(VoiceFetch.WAITING, started = true))
        // A second tap takes the request back; the small automatic download carries on quietly.
        assertEquals(FetchTap(VoiceFetch.FETCHING, startDownload = false), VoiceTaps.tap(VoiceFetch.WAITING, tapStarted = false))
        assertFalse(VoiceTaps.landed(VoiceFetch.FETCHING, started = true))
        assertEquals(FetchTap(VoiceFetch.READY, startDownload = false), VoiceTaps.tap(VoiceFetch.READY, tapStarted = false))
    }

    @Test fun aSecondTapCancelsADownloadATapStarted() {
        // Large or metered clips wait for a tap; that tap starts the transfer, and so does a retry.
        assertEquals(FetchTap(VoiceFetch.WAITING, startDownload = true), VoiceTaps.tap(VoiceFetch.MANUAL, tapStarted = false))
        assertEquals(FetchTap(VoiceFetch.WAITING, startDownload = true), VoiceTaps.tap(VoiceFetch.FAILED, tapStarted = false))
        // Taking it back stops the bytes and waits for a tap again, as the label says.
        assertEquals(FetchTap(VoiceFetch.MANUAL, startDownload = false, cancelDownload = true),
            VoiceTaps.tap(VoiceFetch.WAITING, tapStarted = true))
    }

    @Test fun aDownloadThatLandsAfterHomeOrALockDoesNotStartPlaying() {
        assertFalse(VoiceTaps.landed(VoiceFetch.WAITING, started = false))
        assertTrue(VoiceTaps.landed(VoiceFetch.WAITING, started = true))
    }

    @Test fun mediaDownloadsNeverTakeTheApiAndEventStreamSlots() {
        assertTrue(Api.media.dispatcher !== Api.http.dispatcher)
        assertTrue(Api.media.dispatcher !== Api.sse.dispatcher)
        assertEquals(2, Api.media.dispatcher.maxRequestsPerHost)
        assertEquals(Api.http.interceptors, Api.media.interceptors) // still signed
        assertFalse(Api.media.followRedirects)
    }

    // ------------------------------------------------------------ decode bounds
    private class FakeClock(var t: Long = 0)

    private fun pumpOf(steps: List<PumpStep>, clock: FakeClock, stepMs: Long = 20): Pair<() -> PumpStep, () -> Int> {
        var i = 0
        // A loop that ignores its bounds fails here instead of hanging the suite.
        return { check(i < 100_000) { "runaway decode loop" }; clock.t += stepMs; steps.getOrElse(i++) { steps.last() } } to { i }
    }

    @Test fun theDecodeLoopEndsReleasesAndReportsWhy() {
        var closed = 0
        val clock = FakeClock()
        val (done, _) = pumpOf(listOf(PumpStep.PROGRESS, PumpStep.IDLE, PumpStep.PROGRESS, PumpStep.END), clock)
        assertEquals(PumpEnd.DONE, DecodeBounds.pump(done, { closed++ }, { clock.t }, budgetMs = 10_000, idleTurns = 3))
        assertEquals(1, closed)
        // A decoder that stops answering: the idle bound ends it.
        val (stuck, turns) = pumpOf(listOf(PumpStep.PROGRESS, PumpStep.IDLE), clock)
        assertEquals(PumpEnd.STALLED, DecodeBounds.pump(stuck, { closed++ }, { clock.t }, budgetMs = 10_000_000, idleTurns = 50))
        assertEquals(51, turns())
        assertEquals(2, closed)
        // One that trickles output forever: the time budget ends it.
        val (slow, _) = pumpOf(listOf(PumpStep.PROGRESS), clock, stepMs = 1000)
        assertEquals(PumpEnd.OUT_OF_TIME, DecodeBounds.pump(slow, { closed++ }, { clock.t }, budgetMs = 30_000, idleTurns = 3))
        assertEquals(3, closed)
    }

    @Test fun theDecoderIsReleasedWhenOpeningStepsOrCancellationThrow() {
        var closed = 0
        val clock = FakeClock()
        for (fail in listOf("open", "step", "cancel")) {
            val thrown = runCatching {
                DecodeBounds.pump(step = { if (fail == "step") error("codec died") else PumpStep.PROGRESS },
                    close = { closed++ }, now = { clock.t }, budgetMs = 1000,
                    checkActive = { if (fail == "cancel") throw java.util.concurrent.CancellationException("left the chat") },
                    open = { if (fail == "open") error("no decoder for this format") })
            }.exceptionOrNull()
            assertTrue(fail, thrown != null)
        }
        assertEquals(3, closed)
    }

    @Test fun theDecodeBudgetGrowsWithTheClip() {
        assertEquals(60_000L, DecodeBounds.budgetMs(0))
        assertEquals(15_000L + 15_000L, DecodeBounds.budgetMs(60_000))
        assertEquals(15_000L + 900_000L, DecodeBounds.budgetMs(3_600_000))
        assertTrue(DecodeBounds.MAX_BYTES >= VoiceFetchRules.AUTO_BYTES)
    }

    // ------------------------------------------------------------ playback lifecycle and focus
    @Test fun configurationChangesKeepTheNotePlaying() {
        assertEquals(PlaybackAct.KEEP, PlaybackRules.onActivityStop(changingConfigurations = true))
        assertEquals(PlaybackAct.PAUSE, PlaybackRules.onActivityStop(changingConfigurations = false))
        assertEquals(PlaybackAct.KEEP, PlaybackRules.onScreenLeave(changingConfigurations = true))
        assertEquals(PlaybackAct.STOP, PlaybackRules.onScreenLeave(changingConfigurations = false))
    }

    @Test fun focusLossesPauseDuckAndComeBack() {
        // A navigation prompt: pause, then resume when it ends.
        assertEquals(FocusAct.PAUSE_FOR_NOW, Focus.act(Focus.LOSS_TRANSIENT, playing = true, pausedForNow = false, ducked = false))
        assertEquals(FocusAct.RESUME, Focus.act(Focus.GAIN, playing = false, pausedForNow = true, ducked = false))
        // A notification sound that allows ducking: quieter, then full volume.
        assertEquals(FocusAct.DUCK, Focus.act(Focus.LOSS_TRANSIENT_CAN_DUCK, playing = true, pausedForNow = false, ducked = false))
        assertEquals(FocusAct.NONE, Focus.act(Focus.LOSS_TRANSIENT_CAN_DUCK, playing = true, pausedForNow = false, ducked = true))
        assertEquals(FocusAct.RESTORE, Focus.act(Focus.GAIN, playing = true, pausedForNow = false, ducked = true))
        // Another player taking over: pause for good, also from a transient pause.
        assertEquals(FocusAct.PAUSE, Focus.act(Focus.LOSS, playing = true, pausedForNow = false, ducked = false))
        assertEquals(FocusAct.PAUSE, Focus.act(Focus.LOSS, playing = false, pausedForNow = true, ducked = false))
        // Nothing to do when nothing plays, and a gain after the user paused does not restart the note.
        assertEquals(FocusAct.NONE, Focus.act(Focus.LOSS_TRANSIENT, playing = false, pausedForNow = false, ducked = false))
        assertEquals(FocusAct.NONE, Focus.act(Focus.GAIN, playing = false, pausedForNow = false, ducked = false))
        assertEquals(listOf(1, -1, -2, -3), listOf(Focus.GAIN, Focus.LOSS, Focus.LOSS_TRANSIENT, Focus.LOSS_TRANSIENT_CAN_DUCK))
    }

    // ------------------------------------------------------------ focus sequences
    private class FakePort(var grant: Boolean = true) : FocusPort {
        var requests = 0; var abandons = 0
        override fun request(): Boolean { requests++; return grant }
        override fun abandon() { abandons++ }
    }

    @Test fun aDuckThenATransientLossStillResumesAtFullVolume() {
        val g = FocusGate(FakePort())
        assertTrue(g.acquire())
        assertEquals(FocusAct.DUCK, g.change(Focus.LOSS_TRANSIENT_CAN_DUCK, playing = true))
        assertEquals(FocusAct.PAUSE_FOR_NOW, g.change(Focus.LOSS_TRANSIENT, playing = true))
        assertFalse(g.ducked)
        assertEquals(FocusAct.RESUME, g.change(Focus.GAIN, playing = false))
        assertEquals(FocusAct.RESUME, Focus.act(Focus.GAIN, playing = false, pausedForNow = true, ducked = true))
    }

    @Test fun aPauseWhileDuckedDoesNotLeaveTheNextStartQuiet() {
        val g = FocusGate(FakePort())
        g.acquire()
        g.change(Focus.LOSS_TRANSIENT_CAN_DUCK, playing = true)
        assertTrue(g.ducked)
        g.held()  // any pause
        assertFalse(g.ducked)
        g.change(Focus.LOSS_TRANSIENT_CAN_DUCK, playing = true)
        g.release()  // the user paused, or the screen left
        assertFalse(g.ducked || g.pausedForNow)
    }

    @Test fun playDuringATransientLossAsksForFocusAgainAndHonorsTheAnswer() {
        val port = FakePort()
        val g = FocusGate(port)
        assertTrue(g.acquire())
        g.change(Focus.LOSS_TRANSIENT, playing = true)  // another app holds focus; our request stays registered
        port.grant = false
        assertFalse("must not start while another app owns focus", g.acquire())
        assertEquals(2, port.requests)
        assertTrue(g.pausedForNow)  // the user's play is not lost: it resumes when focus returns
        assertEquals(FocusAct.RESUME, g.change(Focus.GAIN, playing = false))
        port.grant = true
        assertTrue(g.acquire())
        assertEquals(3, port.requests)
        g.release(); g.release()
        assertEquals(1, port.abandons)
        assertEquals(0, FakePort().also { FocusGate(it).release() }.abandons)
    }

    // ------------------------------------------------------------ previews from merged history
    @Test fun anEditedOlderVoiceNoteNeverReplacesANewerPreview() {
        val voice = Msg("v", "c", "user", "text", "", JSONObject().put("attachments", org.json.JSONArray()
            .put(JSONObject().put("kind", "voice").put("transcript", "older words"))), 100.0, 5)
        val newer = Msg("t", "c", "user", "text", "the newer text", JSONObject(), 101.0, 6)
        val chat = Chat("c", "Chat", "chat", updated = 101.0, unread = 0, pinned = false, archived = false, preview = "the newer text", lastRole = "user")
        val out = withPreviews(mapOf("c" to chat), mapOf("c" to listOf(voice, newer)), batch = listOf(voice))
        assertEquals("the newer text", out.getValue("c").preview)
        // The voice note alone: its transcript is the preview.
        val alone = withPreviews(mapOf("c" to chat.copy(updated = 100.0)), mapOf("c" to listOf(voice)), listOf(voice))
        assertEquals("older words", alone.getValue("c").preview)
        // Only an old page is loaded: the server's preview stays.
        val old = withPreviews(mapOf("c" to chat.copy(updated = 500.0, preview = "server")), mapOf("c" to listOf(voice)), listOf(voice))
        assertEquals("server", old.getValue("c").preview)
    }

    // ------------------------------------------------------------ reset cancels media transfers
    @Test fun resettingTheSessionCancelsMediaTransfersToo() = kotlinx.coroutines.runBlocking {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { listener ->
            val connected = java.util.concurrent.CountDownLatch(1)
            val server = kotlin.concurrent.thread(isDaemon = true) {
                runCatching {
                    listener.accept().use { sock ->
                        val r = sock.getInputStream().bufferedReader()
                        while (!r.readLine().isNullOrEmpty()) { }
                        connected.countDown()
                        while (sock.getInputStream().read() != -1) { } // never answers
                    }
                }
            }
            // Same dispatcher as Api.media; the signer is left out because no session is paired here.
            val client = Api.media.newBuilder().apply { interceptors().clear() }.build()
            val call = client.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:${listener.localPort}/v1/media/x").build())
            val result = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { runCatching { call.awaitBody { it.body.string() } } }
            assertTrue(connected.await(3, java.util.concurrent.TimeUnit.SECONDS))
            Api.cancelCalls()
            assertTrue("a media transfer of the old session must stop", call.isCanceled())
            kotlinx.coroutines.withTimeout(3000) { assertTrue(result.await().isFailure) }
            server.join(2000)
        }
    }
}
