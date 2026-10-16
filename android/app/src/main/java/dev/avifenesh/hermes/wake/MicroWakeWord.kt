package dev.avifenesh.hermes.wake

import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Adapted from Home Assistant Android, Apache-2.0,
 * 110809fb146429a613cabeaa0eb2165be57939b9/microwakeword.
 * JNI owns one thread-confined native engine. Only 160-sample steps are accepted so upstream
 * processAudio's detection return cannot discard subsequent feature frames. There is deliberately
 * no nativeReset binding: close and recreate to clear the model's resource-variable state too.
 */
internal class MicroWakeWord(
    modelBuffer: ByteBuffer,
    featureStepSizeMs: Int,
    probabilityCutoff: Float,
    slidingWindowSize: Int,
) : Closeable {
    private var nativeHandle: Long

    init {
        require(modelBuffer.isDirect && modelBuffer.capacity() > 0)
        require(featureStepSizeMs == 10 && slidingWindowSize > 0)
        require(probabilityCutoff.isFinite() && probabilityCutoff in 0f..1f)
        libraryLoaded
        nativeHandle = nativeCreate(modelBuffer, 16000, featureStepSizeMs, probabilityCutoff, slidingWindowSize)
        check(nativeHandle != 0L) { "Failed to create native microWakeWord engine" }
    }

    fun processAudio(samples: ShortArray): Boolean {
        check(nativeHandle != 0L) { "microWakeWord is closed" }
        require(samples.size == 160)
        return nativeProcessAudio(nativeHandle, samples)
    }

    fun snapshot(): IntArray {
        check(nativeHandle != 0L)
        return nativeSnapshot(nativeHandle)
    }

    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0
        }
    }

    private companion object {
        val libraryLoaded: Unit by lazy { System.loadLibrary("microwakeword") }
        @JvmStatic external fun nativeCreate(modelBuffer: ByteBuffer, sampleRate: Int,
            featureStepSizeMs: Int, probabilityCutoff: Float, slidingWindowSize: Int): Long
        @JvmStatic external fun nativeProcessAudio(handle: Long, samples: ShortArray): Boolean
        @JvmStatic external fun nativeSnapshot(handle: Long): IntArray
        @JvmStatic external fun nativeDestroy(handle: Long)
    }
}
