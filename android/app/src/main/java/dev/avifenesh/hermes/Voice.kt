package dev.avifenesh.hermes

import android.animation.ValueAnimator
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import kotlin.math.max

// ---------------------------------------------------------------- waveforms
object Waves {
    const val BUCKETS = 64
    // A 64-peak wave is about 300 bytes, so 256 KB holds several hundred voice notes: one long chat
    // history scrolls back and forth without decoding a clip twice.
    private val cache = WaveCache(256 * 1024)
    // At most two decodes at once: opening a chat full of voice notes must not take every core.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val decoding = Dispatchers.Default.limitedParallelism(2)

    fun cached(id: String): Wave? = cache.get(id)

    /** Waveform for server media [id] (the cache key; file names collide). Off the main thread. */
    suspend fun load(id: String, f: File): Wave =
        cache.get(id) ?: withContext(decoding) { decode(f) }.also { cache.put(id, it) }

    /**
     * Decode the first audio track to PCM with MediaExtractor + MediaCodec and keep the peak of
     * each slice ([PeakMeter]). Duration is measured from the decoded frames, falling back to the
     * container's. [DecodeBounds] caps the file size, the whole decode's time and a stalled
     * decoder; the codec and extractor are released on every exit. A file the platform cannot
     * decode, or one past a bound, yields null peaks, never an exception.
     */
    suspend fun decode(f: File, buckets: Int = BUCKETS): Wave {
        val ex = MediaExtractor()
        var containerMs = 0L
        try {
            ex.setDataSource(f.path)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return Wave(0, null)
            val fmt = ex.getTrackFormat(track)
            if (fmt.containsKey(MediaFormat.KEY_DURATION)) containerMs = fmt.getLong(MediaFormat.KEY_DURATION) / 1000
            if (f.length() > DecodeBounds.MAX_BYTES) return Wave(containerMs, null)
            ex.selectTrack(track)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            var meter = PeakMeter(rate)
            val info = MediaCodec.BufferInfo()
            var shorts = ShortArray(0)
            var floats = FloatArray(0)
            var inputDone = false
            val context = currentCoroutineContext()
            val end = DecodeBounds.pump(step = {
                var moved = false
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        moved = true
                        val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                when {
                    o >= 0 -> {
                        val out = c.getOutputBuffer(o)
                        if (out != null && info.size > 0) {
                            out.position(info.offset); out.limit(info.offset + info.size)
                            out.order(ByteOrder.nativeOrder())
                            if (float) {
                                val fb = out.asFloatBuffer(); val n = fb.remaining()
                                if (floats.size < n) floats = FloatArray(n)
                                fb.get(floats, 0, n); meter.addFloat(floats, n, channels)
                            } else {
                                val sb = out.asShortBuffer(); val n = sb.remaining()
                                if (shorts.size < n) shorts = ShortArray(n)
                                sb.get(shorts, 0, n); meter.addPcm16(shorts, n, channels)
                            }
                        }
                        c.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) PumpStep.END else PumpStep.PROGRESS
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = c.outputFormat
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        if (meter.frames == 0L) meter = PeakMeter(rate)
                        PumpStep.PROGRESS
                    }
                    moved -> PumpStep.PROGRESS
                    else -> PumpStep.IDLE
                }
            }, close = {
                runCatching { c.stop() }
                c.release()
            }, now = { android.os.SystemClock.elapsedRealtime() }, budgetMs = DecodeBounds.budgetMs(containerMs),
                checkActive = { context.ensureActive() }, open = {
                c.configure(fmt, null, null, 0)
                c.start()
            })
            if (end != PumpEnd.DONE) return Wave(containerMs, null)  // a partial wave would misdraw the clip
            val measured = if (meter.frames > 0) meter.frames * 1000 / rate else 0L
            return Wave(if (measured > 0) measured else containerMs, meter.finish(buckets))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Wave(containerMs, null)
        } finally {
            ex.release()
        }
    }
}

// ---------------------------------------------------------------- playback
/** One voice message plays at a time, app-wide, and the player outlives the Activity (process scope):
 *  a rotation keeps the note going. Main thread only (Compose callbacks, lifecycle, MediaPlayer and
 *  audio-focus listeners all run on the main looper). */
object VoicePlayer {
    /** Server media id loaded in the player (playing or paused), else null. */
    val current = mutableStateOf<String?>(null)
    val playing = mutableStateOf(false)
    val position = mutableLongStateOf(0L)
    val duration = mutableLongStateOf(0L)
    /** Shared by every message, like a system player: once 1.5x, the next note plays at 1.5x too. */
    val speed = mutableFloatStateOf(1f)
    private var mp: MediaPlayer? = null
    private var gate: FocusGate? = null
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    init {
        check(AudioManager.AUDIOFOCUS_GAIN == Focus.GAIN && AudioManager.AUDIOFOCUS_LOSS == Focus.LOSS &&
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT == Focus.LOSS_TRANSIENT &&
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK == Focus.LOSS_TRANSIENT_CAN_DUCK)
    }

    fun toggle(c: Context, id: String, f: File) {
        if (current.value == id && mp != null) { if (playing.value) pause() else resume(c) } else play(c, id, f)
    }

    fun play(c: Context, id: String, f: File, fromMs: Long = 0) {
        stop()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(attrs)
            p.setDataSource(f.path)
            p.prepare()
        } catch (e: Exception) { p.release(); throw e }
        p.setOnCompletionListener { if (mp === it) stop() }
        p.setOnErrorListener { m, _, _ -> if (mp === m) stop(); true }
        mp = p
        current.value = id
        duration.longValue = p.duration.toLong().coerceAtLeast(0)
        if (fromMs > 0) p.seekTo(fromMs, MediaPlayer.SEEK_CLOSEST)
        position.longValue = fromMs
        resume(c)
    }

    fun resume(c: Context) {
        val p = mp ?: return
        // Asked every time: a request kept through a transient loss does not mean focus is ours again.
        if (!gate(c).acquire()) { Ui.say(R.string.voice_audio_busy); return }
        start(p)
    }

    private fun start(p: MediaPlayer) {
        p.setVolume(1f, 1f)
        // A non-zero speed on a prepared player starts it, so speed is applied only here, never while paused.
        runCatching { p.playbackParams = p.playbackParams.setSpeed(speed.floatValue) }
        if (!p.isPlaying) p.start()
        playing.value = true
    }

    /** The user, the Activity stopping, or a permanent focus loss: pause and give focus back. */
    fun pause() {
        hold()
        gate?.release()
    }

    /** Any pause: keep the position and bring a ducked volume back up. */
    private fun hold() {
        val p = mp ?: return
        if (p.isPlaying) p.pause()
        p.setVolume(1f, 1f)
        gate?.held()
        position.longValue = p.currentPosition.toLong()
        playing.value = false
    }

    fun seek(id: String, ms: Long) {
        val p = mp ?: return
        if (current.value != id) return
        p.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
        position.longValue = ms
    }

    fun cycleSpeed() {
        speed.floatValue = VoiceMath.nextSpeed(speed.floatValue)
        val p = mp
        if (p != null && playing.value) runCatching { p.playbackParams = p.playbackParams.setSpeed(speed.floatValue) }
    }

    fun tick() {
        val p = mp ?: return
        if (playing.value) position.longValue = p.currentPosition.toLong()
    }

    fun stop() {
        val p = mp
        mp = null
        current.value = null; playing.value = false; position.longValue = 0; duration.longValue = 0
        if (p != null) { runCatching { p.stop() }; p.release() }
        gate?.release()
    }

    private fun onFocus(change: Int) {
        val p = mp ?: return
        when (gate?.change(change, playing.value) ?: return) {
            FocusAct.PAUSE -> pause()
            FocusAct.PAUSE_FOR_NOW -> hold()
            FocusAct.DUCK -> p.setVolume(0.2f, 0.2f)
            FocusAct.RESTORE -> p.setVolume(1f, 1f)
            FocusAct.RESUME -> start(p)
            FocusAct.NONE -> Unit
        }
    }

    private fun gate(c: Context): FocusGate = gate ?: FocusGate(SystemFocus(
        c.applicationContext.getSystemService(AudioManager::class.java),
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(::onFocus).build())).also { gate = it }
}

/** [FocusPort] on AudioManager with one request, asked again before every start. */
private class SystemFocus(private val am: AudioManager?, private val req: AudioFocusRequest) : FocusPort {
    override fun request(): Boolean = am == null || am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    override fun abandon() { am?.abandonAudioFocusRequest(req) }
}

/**
 * Ties voice playback to the screen showing it: Home or screen off pauses, leaving the screen
 * stops, and an Activity recreated for a rotation, theme or font change keeps the note playing
 * at its position ([PlaybackRules]).
 */
@Composable
fun VoicePlaybackScope() {
    val owner = LocalLifecycleOwner.current
    val activity = LocalActivity.current
    DisposableEffect(owner, activity) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && PlaybackRules.onActivityStop(activity?.isChangingConfigurations == true) == PlaybackAct.PAUSE)
                VoicePlayer.pause()
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            if (PlaybackRules.onScreenLeave(activity?.isChangingConfigurations == true) == PlaybackAct.STOP) VoicePlayer.stop()
        }
    }
    val playing = VoicePlayer.playing.value
    // 20 position reads a second while playing and visible: finer than one bar of a short clip.
    VisibleEffect(playing) { while (VoicePlayer.playing.value) { VoicePlayer.tick(); delay(50) } }
}

// ---------------------------------------------------------------- fetching
object VoiceMedia {
    /** Media ids whose download failed in this process: scrolling back does not refetch them on its own. */
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())
    fun failedBefore(id: String) = id in failed
    fun noteFailure(id: String) { failed.add(id) }
    fun noteSuccess(id: String) { failed.remove(id) }

    /** The clip already on the phone (downloaded before, or this phone's own recording), else null. */
    fun local(c: Context, id: String, name: String, size: Long?): File? =
        mediaCacheFile(c, id, name).takeIf { it.isFile && it.length() > 0 && (size == null || it.length() == size) }

    fun metered(c: Context): Boolean =
        runCatching { c.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered }.getOrNull() ?: true
}

// ---------------------------------------------------------------- bubble
/**
 * A voice attachment, sent or received. The clip comes from the phone when it is already there;
 * otherwise a real voice note under [VoiceFetchRules.AUTO_BYTES] on an unmetered network downloads
 * on its own and anything else waits for a tap. A tap during a download plays the note as soon as
 * it lands. Waveforms decode off the main thread; [VoicePlayer] plays.
 */
@Composable
fun VoiceBubble(a: JSONObject, mine: Boolean, modifier: Modifier = Modifier,
                fetch: suspend (Context, String, String, Long?) -> File = { c, id, name, size -> fetchMedia(c, id, name, size) }) {
    val ctx = LocalContext.current
    val id = a.str("id").ifBlank { a.str("media") }
    val name = a.str("file_name")
    val size = a.optString("size").toLongOrNull()
    val voiceKind = a.str("kind") == "voice"
    // Server-supplied length when present; otherwise measured locally from the decoded clip.
    val hint = a.optLong("duration_ms", 0L).coerceAtLeast(0)
    var file by remember(id) { mutableStateOf(VoiceMedia.local(ctx, id, name, size)) }
    var wave by remember(id) { mutableStateOf(Waves.cached(id)) }
    // Decided before the first frame, so a local or auto-downloading clip never flashes "tap to load".
    var state by remember(id) {
        mutableStateOf(when {
            file != null -> VoiceFetch.READY
            VoiceMedia.failedBefore(id) -> VoiceFetch.FAILED
            VoiceFetchRules.auto(voiceKind, size, VoiceMedia.metered(ctx), false) -> VoiceFetch.FETCHING
            else -> VoiceFetch.MANUAL
        })
    }
    // A download a tap started: its own job, so a second tap can cancel the transfer.
    var tapJob by remember(id) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun play(f: File, fromMs: Long = 0) {
        runCatching { VoicePlayer.play(ctx, id, f, fromMs) }.onFailure { Ui.say(R.string.voice_cant_play) }
    }
    suspend fun download() {
        val got = try { fetch(ctx, id, name, size) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        tapJob = null
        if (got == null) { VoiceMedia.noteFailure(id); state = VoiceFetch.FAILED; return }
        VoiceMedia.noteSuccess(id)
        file = got
        val wanted = VoiceTaps.landed(state, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        state = VoiceFetch.READY
        if (wanted) play(got)
        if (wave == null) wave = Waves.load(id, got)
    }
    LaunchedEffect(id) {
        val have = file
        if (have != null) { if (wave == null) wave = Waves.load(id, have) }
        else if (state == VoiceFetch.FETCHING) download()  // the automatic download, decided above
    }
    val current = VoicePlayer.current.value == id
    val w = wave
    val dur = when {
        current && VoicePlayer.duration.longValue > 0 -> VoicePlayer.duration.longValue
        w != null && w.durationMs > 0 -> w.durationMs
        else -> hint
    }
    VoiceBubbleContent(
        mine = mine, peaks = w?.peaks, ready = w != null, fetch = state, sizeBytes = size, tapStarted = tapJob != null,
        durationMs = dur, positionMs = { if (VoicePlayer.current.value == id) VoicePlayer.position.longValue else 0L },
        current = current, playing = current && VoicePlayer.playing.value, speed = VoicePlayer.speed.floatValue,
        transcript = a.str("transcript"),
        onToggle = {
            val f = file
            when {
                f != null -> runCatching { VoicePlayer.toggle(ctx, id, f) }.onFailure { Ui.say(R.string.voice_cant_play) }
                else -> {
                    val t = VoiceTaps.tap(state, tapStarted = tapJob != null)
                    state = t.state
                    if (t.cancelDownload) { tapJob?.cancel(); tapJob = null }
                    if (t.startDownload) tapJob = scope.launch { download() }
                }
            }
        },
        onSeek = { ms ->
            val f = file
            if (VoicePlayer.current.value == id) VoicePlayer.seek(id, ms) else if (f != null) play(f, ms)
        },
        onSpeed = { VoicePlayer.cycleSpeed() },
        modifier = modifier,
    )
}

/**
 * Stateless voice bubble. [positionMs] is read while drawing and, for the time label, once a
 * second, so a playing note redraws its bars without recomposing at playback rate. [peaks] null
 * with [ready] true means the clip could not be decoded: flat bars, still playable and seekable.
 */
@Composable
fun VoiceBubbleContent(
    mine: Boolean, peaks: FloatArray?, ready: Boolean, fetch: VoiceFetch, durationMs: Long, positionMs: () -> Long,
    current: Boolean, playing: Boolean, speed: Float, transcript: String,
    onToggle: () -> Unit, onSeek: (Long) -> Unit, onSpeed: () -> Unit,
    modifier: Modifier = Modifier, transcriptOpen: Boolean = false, sizeBytes: Long? = null, tapStarted: Boolean = false,
) {
    val p = P
    var open by remember(transcript) { mutableStateOf(transcriptOpen) }
    val shell = if (mine) Modifier.fillMaxWidth()
    else Modifier.widthIn(max = 300.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.s2)
        .padding(start = 2.dp, end = 4.dp, top = 2.dp, bottom = 6.dp)
    val pos by remember(current) { derivedStateOf { if (current) positionMs() / 1000 * 1000 else 0L } }
    Column(modifier.then(shell)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayButton(playing, fetch, durationMs, tapStarted, onToggle)
            Spacer(Modifier.width(4.dp))
            Waveform(peaks, ready, durationMs, positionMs, pos, current, mine, onSeek, Modifier.weight(1f).height(48.dp))
            Spacer(Modifier.width(2.dp))
            SpeedChip(speed, mine, onSpeed)
        }
        FlowRow(Modifier.fillMaxWidth().padding(start = 52.dp), horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically) {
            val length = if (durationMs > 0) VoiceMath.clock(durationMs, total = true) else null
            val time = when {
                fetch == VoiceFetch.FAILED -> stringResource(R.string.voice_load_failed)
                fetch == VoiceFetch.WAITING -> stringResource(R.string.common_loading)
                fetch == VoiceFetch.MANUAL -> listOfNotNull(length, sizeBytes?.let { sizeStr(it) }, stringResource(R.string.voice_tap_to_load)).joinToString(" · ")
                current -> VoiceMath.clock(pos) + " / " + (length ?: "--:--")
                length != null -> length
                else -> stringResource(R.string.voice_message)
            }
            Text(time, style = mono(11.5f, 500, when { fetch == VoiceFetch.FAILED -> p.attt; current || fetch == VoiceFetch.WAITING -> p.acct; else -> p.mu }),
                modifier = Modifier.padding(end = 8.dp, bottom = if (transcript.isBlank()) 2.dp else 0.dp))
            if (transcript.isNotBlank()) {
                val toggleLabel = stringResource(if (open) R.string.voice_transcript_hide else R.string.voice_transcript_show)
                Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                    .semantics { stateDescription = str(if (open) R.string.voice_transcript_expanded else R.string.voice_transcript_collapsed) }
                    .clickable(role = Role.Button, onClickLabel = toggleLabel) { open = !open }
                    .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.voice_transcript), style = mono(11.5f, 600, p.mu))
                    Spacer(Modifier.width(4.dp))
                    Icn(if (open) Ic.chevU else Ic.chevD, p.mu, 14.dp)
                }
            }
        }
        if (open && transcript.isNotBlank()) Text(transcript, style = sans(14f, 400, if (mine) p.tx else p.tx2, 20f).content(),
            modifier = Modifier.padding(start = 52.dp, end = 6.dp, bottom = 4.dp))
    }
}

@Composable
private fun PlayButton(playing: Boolean, fetch: VoiceFetch, durationMs: Long, tapStarted: Boolean, onClick: () -> Unit) {
    val p = P
    val length = if (durationMs > 0) VoiceMath.spoken(durationMs) else null
    val label = when {
        fetch == VoiceFetch.FAILED -> stringResource(R.string.voice_retry_desc)
        fetch == VoiceFetch.WAITING && tapStarted -> stringResource(R.string.voice_loading_cancel_desc)
        fetch == VoiceFetch.WAITING -> stringResource(R.string.voice_loading_autoplay_desc)
        fetch == VoiceFetch.MANUAL -> if (length != null) stringResource(R.string.voice_load_play_length_desc, length) else stringResource(R.string.voice_load_play_desc)
        playing -> stringResource(R.string.voice_pause_desc)
        else -> if (length != null) stringResource(R.string.voice_play_length_desc, length) else stringResource(R.string.voice_play_desc)
    }
    val quiet = fetch == VoiceFetch.FAILED || fetch == VoiceFetch.MANUAL
    Box(Modifier.size(48.dp).clip(CircleShape).semantics { contentDescription = label }
        .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (quiet) p.s4 else p.acc), contentAlignment = Alignment.Center) {
            when {
                fetch == VoiceFetch.WAITING -> LoadingRing(p.ink)
                fetch == VoiceFetch.FAILED -> Icn(Ic.refresh, p.tx, 17.dp)
                fetch == VoiceFetch.MANUAL -> Icn(Ic.download, p.tx, 17.dp)
                else -> Icn(if (playing) Ic.pause else Ic.play, p.ink, 17.dp)
            }
        }
    }
}

/** A thin spinning arc while a tapped note downloads; a still arc when motion is off. */
@Composable
private fun LoadingRing(color: androidx.compose.ui.graphics.Color) {
    val still = rememberReducedMotion()
    val turn = if (still) 0f else {
        val t by rememberInfiniteTransition(label = "voice loading").animateFloat(0f, 360f,
            infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "turn")
        t
    }
    Canvas(Modifier.size(18.dp)) {
        drawArc(color, startAngle = turn - 90f, sweepAngle = 270f, useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

@Composable
private fun SpeedChip(speed: Float, mine: Boolean, onClick: () -> Unit) {
    val p = P
    val label = VoiceMath.speedLabel(speed)
    Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(RoundedCornerShape(12.dp))
        .semantics { contentDescription = str(R.string.voice_speed_desc, label) }
        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.voice_speed_change), onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = mono(11.5f, 600, if (speed != 1f) p.acct else p.tx2), modifier = Modifier.clearAndSetSemantics { }
            .clip(RoundedCornerShape(8.dp)).background(if (mine) p.s4 else p.s3).padding(horizontal = 7.dp, vertical = 3.dp))
    }
}

@Composable
private fun Waveform(
    peaks: FloatArray?, ready: Boolean, durationMs: Long, positionMs: () -> Long, pos: Long, current: Boolean, mine: Boolean,
    onSeek: (Long) -> Unit, modifier: Modifier,
) {
    val seek by rememberUpdatedState(onSeek)
    val p = P
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var scrub by remember { mutableStateOf<Float?>(null) }
    val played = p.acct
    val rest = (if (mine) p.tx2 else p.mu).copy(alpha = 0.42f)
    val pending = p.line2
    fun progress(): Float = scrub ?: if (current && durationMs > 0) positionMs().toFloat() / durationMs else 0f
    Canvas(modifier
        .pointerInput(durationMs, rtl) {
            detectTapGestures { o -> seek(VoiceMath.seekMs(VoiceMath.fraction(o.x, size.width.toFloat(), rtl), durationMs)) }
        }
        .pointerInput(durationMs, rtl) {
            detectHorizontalDragGestures(
                onDragStart = { o -> scrub = VoiceMath.fraction(o.x, size.width.toFloat(), rtl) },
                onDragEnd = { scrub?.let { seek(VoiceMath.seekMs(it, durationMs)) }; scrub = null },
                onDragCancel = { scrub = null },
            ) { change, _ -> scrub = VoiceMath.fraction(change.position.x, size.width.toFloat(), rtl) }
        }
        // Its own node: inside a long-pressable message bubble, which merges its children, TalkBack must still
        // reach the waveform as a separate seek control.
        .semantics(mergeDescendants = true) {
            contentDescription = str(R.string.voice_position_desc)
            stateDescription = if (durationMs > 0) str(R.string.voice_position_state, VoiceMath.spoken(pos), VoiceMath.spoken(durationMs)) else str(R.string.voice_length_unknown)
            if (durationMs > 0) {
                progressBarRangeInfo = ProgressBarRangeInfo(pos / 1000f, 0f..durationMs / 1000f)
                setProgress(str(R.string.voice_seek)) { v -> onSeek(VoiceMath.seekMs(v * 1000f / durationMs, durationMs)); true }
                customActions = listOf(
                    CustomAccessibilityAction(str(R.string.voice_seek_back)) { onSeek(VoiceMath.step(pos, durationMs, -5000)); true },
                    CustomAccessibilityAction(str(R.string.voice_seek_forward)) { onSeek(VoiceMath.step(pos, durationMs, 5000)); true },
                )
            }
        }) {
        val bar = 3.dp.toPx(); val gap = 2.dp.toPx()
        val n = VoiceMath.barCount(size.width, bar, gap)
        if (n == 0) return@Canvas
        val values = if (peaks != null) VoiceMath.pool(peaks, n) else null
        val filled = if (ready) VoiceMath.playedBars(progress(), n) else 0
        val tall = 28.dp.toPx(); val low = 3.dp.toPx()
        for (i in 0 until n) {
            val h = max(low, (values?.get(i) ?: 0.16f) * tall)
            val x = i * (bar + gap)
            drawRoundRect(
                color = when { !ready -> pending; i < filled -> played; else -> rest },
                topLeft = Offset(if (rtl) size.width - x - bar else x, (size.height - h) / 2),
                size = Size(bar, h), cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}

// ---------------------------------------------------------------- agent orb
/** True when the system's animator duration scale is 0 (Remove animations): motion is off. */
@Composable
fun rememberReducedMotion(): Boolean = remember { !ValueAnimator.areAnimatorsEnabled() }

/**
 * The agent in chat, in the Talk orb's language: a round orb that turns accent when live. Idle is
 * a dim orb with an accent core. Live breathes like Talk's orb (0.92 to 1.08 every 700 ms) under
 * a fading halo; with motion off it is a still accent orb. Decorative: the row beside it says who
 * is speaking or working.
 */
@Composable
fun AgentOrb(size: Dp = 24.dp, live: Boolean = false, still: Boolean = rememberReducedMotion()) {
    val p = P
    if (!live) {
        Box(Modifier.size(size).clip(CircleShape).background(p.s3), contentAlignment = Alignment.Center) {
            Box(Modifier.size(size * 0.36f).clip(CircleShape).background(p.acct))
        }
        return
    }
    val core = @Composable { Box(Modifier.size(size * 0.36f).clip(CircleShape).background(p.ink.copy(alpha = 0.55f))) }
    if (still) {
        Box(Modifier.size(size).clip(CircleShape).background(p.acc), contentAlignment = Alignment.Center) { core() }
        return
    }
    val t = rememberInfiniteTransition(label = "agent orb")
    val pulse by t.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    val halo by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearOutSlowInEasing)), label = "halo")
    val ring = p.acc
    Box(Modifier.size(size)
        .drawBehind { drawCircle(ring.copy(alpha = 0.34f * (1f - halo)), radius = this.size.minDimension / 2f * (1f + 0.7f * halo)) }
        .graphicsLayer { scaleX = pulse; scaleY = pulse }
        .clip(CircleShape).background(p.acc), contentAlignment = Alignment.Center) { core() }
}
