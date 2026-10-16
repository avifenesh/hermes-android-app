package dev.avifenesh.hermes

import android.content.res.AssetManager
import dev.avifenesh.hermes.wake.MicroWakeWord
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Offline microWakeWord v2. Confined to the microphone thread; detection is an event, not a score. */
class WakeWord(model: ByteArray, private var cutoff: Float = DEFAULT_CUTOFF) : AutoCloseable {
    private val modelBuffer = ByteBuffer.allocateDirect(model.size).apply { put(model); rewind() }
    private var engine: MicroWakeWord? = createEngine()
    // Always feed exactly one feature step to upstream: its early-return-on-hit must not drop
    // later frames in a larger AudioRecord read. Preserve partial steps across calls.
    private val step = ShortArray(STEP)
    private var pending = 0
    private var processed = 0L

    private fun createEngine() = MicroWakeWord(modelBuffer, FEATURE_STEP_MS, cutoff, WINDOW)

    /** Recreate TFLM as well as frontend state. Upstream reset() leaves resource variables alive. */
    fun reset(cutoff: Float = this.cutoff) {
        check(engine != null) { "WakeWord has been closed" }
        require(cutoff.isFinite() && cutoff in 0f..1f)
        engine?.close()
        engine = null
        this.cutoff = cutoff
        pending = 0
        processed = 0
        step.fill(0)
        engine = createEngine()
    }

    /** Every sample is consumed, even after a hit. Optional event positions are stream sample ends. */
    fun feed(pcm: ShortArray, count: Int = pcm.size, onDetection: ((Long) -> Unit)? = null): Boolean {
        val detector = checkNotNull(engine) { "WakeWord has been closed" }
        require(count in 0..pcm.size)
        var offset = 0
        var detected = false
        while (offset < count) {
            val take = minOf(STEP - pending, count - offset)
            pcm.copyInto(step, pending, offset, offset + take)
            offset += take
            pending += take
            if (pending == STEP) {
                processed += STEP
                pending = 0
                if (detector.processAudio(step)) {
                    detected = true
                    onDetection?.invoke(processed)
                }
            }
        }
        return detected
    }

    /** Genuine native diagnostics for the activation meter and fixtures, not an OWW probability. */
    internal fun snapshot(): IntArray = checkNotNull(engine).snapshot()

    override fun close() { engine?.close(); engine = null; step.fill(0); pending = 0 }

    companion object {
        const val RATE = 16000
        const val CHUNK = 1280 // Preserve existing 80 ms microphone batching and handoff timing.
        const val FEATURE_STEP_MS = 10
        const val STEP = 160
        const val WINDOW = 5
        const val DEFAULT_CUTOFF = 0.97f
        const val MODEL_ID = "mww_hey_jarvis_v2"
        const val MODEL_SHA256 = "21a7976add39ee24ec96c63d96b7aaa18e24d1d9824b963e451da8feb4b78b77"
        const val MANIFEST_SHA256 = "b153867d818675d8abcc9dace474afe7f83551ae0d5a9b1d71a98681320185af"
        val PHRASES = listOf(MODEL_ID to "Hey Jarvis")
        private fun verified(assets: AssetManager, name: String, sha256: String): ByteArray =
            assets.open("microwakeword/$name").use { it.readBytes() }.also { bytes ->
                val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                check(actual == sha256) { "Bundled wake model or manifest is corrupt" }
            }

        fun load(assets: AssetManager, cutoff: Float = DEFAULT_CUTOFF): WakeWord {
            verified(assets, "hey_jarvis.json", MANIFEST_SHA256)
            return WakeWord(verified(assets, "hey_jarvis.tflite", MODEL_SHA256), cutoff)
        }

        /** Debug fixture replay. No padding or omitted full steps at the end; positions are samples. */
        fun detectWav(ww: WakeWord, wav: ByteArray, chunk: Int = CHUNK): LongArray {
            require(chunk > 0)
            val pcm = WakePcm.wav(wav)
            ww.reset()
            val hits = mutableListOf<Long>()
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(offset + chunk, pcm.size)
                ww.feed(pcm.copyOfRange(offset, end), onDetection = { hits.add(it) })
                offset = end
            }
            return hits.toLongArray()
        }

        fun label(id: String) = PHRASES.firstOrNull { it.first == id }?.second ?: "Hey Jarvis"
    }
}
