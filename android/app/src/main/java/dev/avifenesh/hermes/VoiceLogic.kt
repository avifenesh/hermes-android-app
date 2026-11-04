package dev.avifenesh.hermes

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** A voice clip's display data: measured duration and normalized peaks (0..1), or null peaks when decoding failed. */
class Wave(val durationMs: Long, val peaks: FloatArray?) {
    val bytes: Int get() = 48 + (peaks?.size ?: 0) * 4
}

/**
 * Peak amplitude per time window over decoded PCM, in bounded memory.
 *
 * Each window keeps the loudest sample of any channel. When [maxWindows] fill up, neighbours
 * merge pairwise (max of both) and the window doubles, so a clip of any length costs at most
 * [maxWindows] floats while every window still covers the same span of time.
 */
class PeakMeter(sampleRate: Int, windowMs: Int = 10, private val maxWindows: Int = 4096) {
    init { require(sampleRate > 0 && windowMs > 0 && maxWindows >= 2 && maxWindows % 2 == 0) }
    var windowFrames: Long = max(1L, sampleRate.toLong() * windowMs / 1000); private set
    var frames: Long = 0; private set
    private val windows = FloatArray(maxWindows)
    private var count = 0
    private var inWindow = 0L
    private var peak = 0f
    val windowCount: Int get() = count + if (inWindow > 0) 1 else 0

    /** Interleaved 16-bit PCM. */
    fun addPcm16(pcm: ShortArray, length: Int = pcm.size, channels: Int = 1) {
        val ch = channels.coerceAtLeast(1)
        var i = 0
        while (i + ch <= length) {
            var p = 0
            for (c in 0 until ch) p = max(p, abs(pcm[i + c].toInt()))
            frame(p / 32768f)
            i += ch
        }
    }

    /** Interleaved float PCM in -1..1. */
    fun addFloat(pcm: FloatArray, length: Int = pcm.size, channels: Int = 1) {
        val ch = channels.coerceAtLeast(1)
        var i = 0
        while (i + ch <= length) {
            var p = 0f
            for (c in 0 until ch) p = max(p, abs(pcm[i + c]))
            frame(p.coerceAtMost(1f))
            i += ch
        }
    }

    private fun frame(p: Float) {
        if (p > peak) peak = p
        frames++
        if (++inWindow >= windowFrames) close()
    }

    private fun close() {
        if (count == maxWindows) {
            for (i in 0 until maxWindows / 2) windows[i] = max(windows[2 * i], windows[2 * i + 1])
            count = maxWindows / 2
            windowFrames *= 2
            // The open window keeps its frames: it is simply longer now. Only full windows close.
            if (inWindow < windowFrames) return
        }
        windows[count++] = peak
        peak = 0f; inWindow = 0
    }

    /** [buckets] peaks normalized to the loudest one; null when no frame was decoded. */
    fun finish(buckets: Int): FloatArray? {
        if (frames == 0L) return null
        val all = FloatArray(windowCount)
        windows.copyInto(all, 0, 0, count)
        if (inWindow > 0) all[count] = peak
        val out = VoiceMath.pool(all, buckets)
        val top = out.maxOrNull() ?: 0f
        if (top > 0f) for (i in out.indices) out[i] /= top
        return out
    }
}

object VoiceMath {
    val SPEEDS = floatArrayOf(1f, 1.5f, 2f)

    /** Resample [src] to [n] values: max-pool when shrinking, nearest when stretching. */
    fun pool(src: FloatArray, n: Int): FloatArray {
        if (n <= 0 || src.isEmpty()) return FloatArray(n.coerceAtLeast(0))
        val out = FloatArray(n)
        for (b in 0 until n) {
            val from = (b.toLong() * src.size / n).toInt()
            val to = max(from + 1, ((b + 1).toLong() * src.size / n).toInt())
            var m = 0f
            for (i in from until to.coerceAtMost(src.size)) if (src[i] > m) m = src[i]
            out[b] = m
        }
        return out
    }

    /** "0:07", "4:12", "1:02:03". Positions floor; a total rounds to the nearest second (a 2.6 s clip reads 0:03). */
    fun clock(ms: Long, total: Boolean = false): String {
        val s = ((if (total) ms + 500 else ms).coerceAtLeast(0) / 1000).let { if (total && ms > 0) max(1L, it) else it }
        return if (s < 3600) "%d:%02d".format(s / 60, s % 60) else "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    }

    /** Spoken form for accessibility: "45 seconds", "1 minute 5 seconds", "1 hour 2 minutes". */
    fun spoken(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 500) / 1000
        fun unit(n: Long, w: String) = "$n $w" + if (n == 1L) "" else "s"
        val h = s / 3600; val m = (s / 60) % 60; val sec = s % 60
        return when {
            h > 0 -> listOfNotNull(unit(h, "hour"), m.takeIf { it > 0 }?.let { unit(it, "minute") }).joinToString(" ")
            m > 0 -> listOfNotNull(unit(m, "minute"), sec.takeIf { it > 0 }?.let { unit(it, "second") }).joinToString(" ")
            else -> unit(sec, "second")
        }
    }

    /** Where along the waveform a touch at [x] lands, 0..1, measured from the start edge (right in RTL). */
    fun fraction(x: Float, width: Float, rtl: Boolean): Float {
        if (width <= 0f) return 0f
        val f = (x / width).coerceIn(0f, 1f)
        return if (rtl) 1f - f else f
    }

    fun seekMs(fraction: Float, durationMs: Long): Long =
        (fraction.coerceIn(0f, 1f) * durationMs.coerceAtLeast(0)).roundToLong().coerceIn(0, durationMs.coerceAtLeast(0))

    /** A relative jump (accessibility skip), kept inside the clip. */
    fun step(positionMs: Long, durationMs: Long, deltaMs: Long): Long =
        (positionMs + deltaMs).coerceIn(0, durationMs.coerceAtLeast(0))

    /** How many of [bars] read as played at [progress]: a bar fills once the playhead passes its middle. */
    fun playedBars(progress: Float, bars: Int): Int = (progress.coerceIn(0f, 1f) * bars).roundToInt().coerceIn(0, bars)

    /** How many bars of [barPx] with [gapPx] between them fit in [widthPx]. */
    fun barCount(widthPx: Float, barPx: Float, gapPx: Float): Int =
        if (widthPx < barPx || barPx <= 0f) 0 else ((widthPx + gapPx) / (barPx + gapPx)).toInt()

    fun nextSpeed(current: Float): Float {
        val i = SPEEDS.indexOfFirst { abs(it - current) < 0.01f }
        return SPEEDS[(i + 1).mod(SPEEDS.size)]
    }

    fun speedLabel(speed: Float): String =
        if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${(speed * 10).roundToInt() / 10f}x"
}

/** Waveforms by server media id (never file name), bounded by [maxBytes]; least recently used goes first. */
class WaveCache(private val maxBytes: Int) {
    private val map = LinkedHashMap<String, Wave>(16, 0.75f, true)
    var bytes = 0; private set

    @Synchronized fun get(id: String): Wave? = map[id]

    @Synchronized fun put(id: String, wave: Wave) {
        if (wave.bytes > maxBytes) return
        map.put(id, wave)?.let { bytes -= it.bytes }
        bytes += wave.bytes
        val eldest = map.entries.iterator()
        while (bytes > maxBytes && eldest.hasNext()) {
            val e = eldest.next()
            if (e.key == id) continue
            bytes -= e.value.bytes; eldest.remove()
        }
    }

    @Synchronized fun size(): Int = map.size
    @Synchronized fun clear() { map.clear(); bytes = 0 }
}
