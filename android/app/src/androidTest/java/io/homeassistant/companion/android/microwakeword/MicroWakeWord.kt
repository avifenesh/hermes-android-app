package io.homeassistant.companion.android.microwakeword

import java.nio.ByteBuffer

/** Test-only JNI binding to the UNMODIFIED pinned Home Assistant reference C++ engine. */
class MicroWakeWord(model: ByteArray, cutoff: Float) : AutoCloseable {
    private val buffer = ByteBuffer.allocateDirect(model.size).apply { put(model); rewind() }
    private var handle: Long
    init {
        loaded
        handle = nativeCreate(buffer, 16000, 10, cutoff, 5)
        check(handle != 0L)
    }
    fun feed(samples: ShortArray): Boolean = nativeProcessAudio(handle, samples)
    override fun close() { if (handle != 0L) { nativeDestroy(handle); handle = 0L } }
    companion object {
        private val loaded: Unit by lazy { System.loadLibrary("mwwreference") }
        @JvmStatic external fun nativeCreate(buffer: ByteBuffer, rate: Int, step: Int, cutoff: Float, window: Int): Long
        @JvmStatic external fun nativeProcessAudio(handle: Long, samples: ShortArray): Boolean
        @JvmStatic external fun nativeReset(handle: Long)
        @JvmStatic external fun nativeDestroy(handle: Long)
    }
}
