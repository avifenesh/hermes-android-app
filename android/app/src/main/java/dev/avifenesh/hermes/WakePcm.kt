package dev.avifenesh.hermes

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Strict local test-fixture decoder. Never silently reinterpret stereo, float, or a different rate. */
internal object WakePcm {
    fun wav(bytes: ByteArray): ShortArray {
        require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "Expected RIFF/WAVE" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val declaredEnd = 8L + (b.getInt(4).toLong() and 0xffffffffL)
        require(declaredEnd in 12L..bytes.size.toLong()) { "Truncated WAV" }
        var pos = 12
        var formatOk = false
        var data: IntRange? = null
        while (pos.toLong() + 8 <= declaredEnd) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = b.getInt(pos + 4).toLong() and 0xffffffffL
            val start = pos + 8
            val end = start.toLong() + size
            require(end <= declaredEnd) { "Truncated WAV chunk" }
            if (id == "fmt ") {
                require(size >= 16 && b.getShort(start).toInt() == 1 && b.getShort(start + 2).toInt() == 1 &&
                    b.getInt(start + 4) == 16000 && b.getInt(start + 8) == 32000 &&
                    b.getShort(start + 12).toInt() == 2 && b.getShort(start + 14).toInt() == 16) {
                    "Fixture must be 16 kHz mono PCM16 WAV"
                }
                formatOk = true
            }
            if (id == "data") {
                require(data == null && size % 2 == 0L) { "Invalid WAV data" }
                data = start until end.toInt()
            }
            val next = end + (size and 1L)
            require(next <= declaredEnd && next <= Int.MAX_VALUE) { "Missing WAV padding" }
            pos = next.toInt()
        }
        require(pos.toLong() == declaredEnd && formatOk && data != null) { "Missing or invalid WAV chunks" }
        val range = checkNotNull(data)
        return ShortArray((range.last - range.first + 1) / 2) { b.getShort(range.first + it * 2) }
    }
}
