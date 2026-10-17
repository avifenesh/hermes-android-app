package dev.avifenesh.hermes

import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class StreamingUploadTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun goldenExactSerializedBytesIncludingEmptyAndBinary() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", requestBodySha256(null))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", requestBodySha256("abc".toRequestBody()))
        for (bytes in listOf(ByteArray(0), "héllo\r\n世界\u0000".toByteArray(), ByteArray(196613) { it.toByte() })) {
            val body = bytes.toRequestBody("application/octet-stream".toMediaType())
            assertEquals(sha(bytes), requestBodySha256(body))
        }
    }

    @Test fun hashingDrainsInsteadOfRetainingTheWholeBody() {
        val chunk = ByteArray(4096) { it.toByte() }
        val digest = MessageDigest.getInstance("SHA-256")
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun writeTo(sink: BufferedSink) {
                repeat(4096) {
                    sink.write(chunk)
                    // The old signer supplied a Buffer that grew with the entire upload.
                    assertTrue("Signing retained the serialized body", sink.buffer.size < 8192)
                    digest.update(chunk)
                }
            }
        }
        val actual = requestBodySha256(body)
        assertEquals(digest.digest().joinToString("") { "%02x".format(it) }, actual)
    }

    @Test fun snapshotsAreRepeatableAfterOriginalMutationAndCloseProviderOnce() {
        val directory = Files.createTempDirectory("upload-test-").toFile()
        val bytes = ByteArray(196613) { (it * 17).toByte() }
        val original = bytes.copyOf()
        var closes = 0
        val source = object : ByteArrayInputStream(bytes) {
            override fun close() { closes++; super.close() }
        }
        try {
            val snapshot = spoolUpload(source, directory) { }
            bytes.fill(0) // A caller changing the source must not change the signed/sent snapshot.
            val body = uploadFileBody(snapshot, "image/png") { }
            assertEquals(1, closes)
            assertEquals(original.size.toLong(), body.contentLength())
            assertEquals("image/png", body.contentType().toString())
            assertEquals(sha(original), requestBodySha256(body))
            repeat(2) {
                val wire = Buffer()
                body.writeTo(wire)
                assertArrayEquals(original, wire.readByteArray())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancellationAndProviderFailureRemovePartialSnapshots() {
        val directory = Files.createTempDirectory("upload-test-").toFile()
        try {
            var checkpoints = 0
            var closed = false
            val source = object : ByteArrayInputStream(ByteArray(196613)) {
                override fun close() { closed = true; super.close() }
            }
            try {
                spoolUpload(source, directory) {
                    if (++checkpoints == 5) throw CancellationException("connection changed")
                }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertTrue(closed)
            assertTrue(directory.listFiles()!!.isEmpty())
            val broken = object : ByteArrayInputStream(ByteArray(196613)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("provider failed")
            }
            try { spoolUpload(broken, directory) { }; fail("Expected provider error") }
            catch (_: IOException) { }
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun epochInvalidationStopsHashingBeforeTheNextSegment() {
        val epoch = SessionEpoch()
        val ticket = epoch.capture()
        val body = object : RequestBody() {
            override fun contentType() = null
            override fun writeTo(sink: BufferedSink) {
                sink.write(ByteArray(8192))
                epoch.invalidate()
                sink.write(ByteArray(8192))
                fail("Stale request body was fully consumed")
            }
        }
        try { requestBodySha256(body) { epoch.commit(ticket) { } }; fail("Expected stale epoch") }
        catch (_: CancellationException) { }
    }
}
