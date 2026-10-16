package dev.avifenesh.hermes

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class WakePcmTest {
    private fun wav(rate: Int = 16000, channels: Int = 1, padChunk: Boolean = false): ByteArray {
        val bytes = ByteArray(if (padChunk) 58 else 48)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(bytes.size - 8).put("WAVE".toByteArray())
        if (padChunk) b.put("JUNK".toByteArray()).putInt(1).put(7).put(0)
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            .putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(4).putShort(Short.MIN_VALUE).putShort(Short.MAX_VALUE)
        return bytes
    }
    private fun rejects(bytes: ByteArray) {
        try { WakePcm.wav(bytes); fail("accepted invalid WAV") } catch (_: IllegalArgumentException) { }
    }
    @Test fun decodesPcmAndPaddedUnknownChunk() {
        for (padded in listOf(false, true)) assertArrayEquals(shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE), WakePcm.wav(wav(padChunk = padded)))
    }
    @Test fun rejectsNon16kMonoAndMalformedContainers() {
        rejects(wav(rate = 48000))
        rejects(wav(channels = 2))
        rejects(ByteArray(0))
        rejects(wav().copyOf(46))
        rejects(wav().also { it[0] = 0 })
        rejects(wav().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(40, Int.MAX_VALUE) })
        rejects(wav().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 3) })
    }
    @Test fun obeysRiffLengthRatherThanTrailingBytes() {
        val valid = wav()
        assertEquals(2, WakePcm.wav(valid + ByteArray(8) { 42 }).size)
    }
}
