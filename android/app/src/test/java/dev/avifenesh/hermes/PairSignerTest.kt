package dev.avifenesh.hermes

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.BufferedReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import kotlin.concurrent.thread

/**
 * OkHttp silently re-sends a request when a pooled connection turns out to be dead. A re-sent pairing signature is a
 * replay the server refuses, so every network attempt must carry its own. Real OkHttp, real loopback sockets.
 */
class PairSignerTest {
    private class Box : AutoCloseable {
        val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}"
        /** Headers (lower-case names) of every POST that reached the server. */
        val posts: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())

        private fun read(r: BufferedReader): Pair<String, Map<String, String>>? {
            val line = r.readLine() ?: return null
            val h = HashMap<String, String>()
            while (true) {
                val l = r.readLine() ?: return null
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                h[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val n = h["content-length"]?.toInt() ?: 0
            val body = CharArray(n)
            var got = 0
            while (got < n) { val k = r.read(body, got, n - got); if (k < 0) return null; got += k }
            return line to h
        }

        private fun ok(s: Socket) = s.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\n\r\n{\"ok\":true}".toByteArray()); flush()
        }

        private val worker = thread(isDaemon = true, name = "pair-signer-box") {
            runCatching {
                // 1st connection: answer the warm-up, then take the pairing POST and drop the connection unanswered.
                listener.accept().use { s ->
                    val r = s.getInputStream().bufferedReader()
                    read(r); ok(s)
                    read(r)?.let { posts += it.second }
                }
                // 2nd connection: OkHttp's silent retry of the same call.
                listener.accept().use { s ->
                    val r = s.getInputStream().bufferedReader()
                    read(r)?.let { posts += it.second }
                    ok(s)
                }
            }
        }

        override fun close() { listener.close(); worker.join(2000) }
    }

    private fun hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun aSilentRetrySendsAFreshSignature() = Box().use { box ->
        val base = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()  // like Api.plain
        base.newCall(Request.Builder().url("${box.url}/warm").build()).execute().use { it.body.string() }
        val body = """{"code":"c0de","server":"${box.url}"}""".toByteArray()
        val signed = mutableListOf<String>()
        var clock = 1_800_000_000L
        val client = pairingClient(base, body, sign = { m -> signed += String(m); "sig-${signed.size}" }, now = { clock++ })
        val res = client.newCall(Request.Builder().url("${box.url}/v1/pair").post(body.toRequestBody("application/json".toMediaType())).build())
            .execute()
        res.use { assertEquals(200, it.code) }
        assertEquals("both attempts reached the server", 2, box.posts.size)
        assertEquals(listOf("sig-1", "sig-2"), box.posts.map { it["x-hm-sig"] })
        assertNotEquals(box.posts[0]["x-hm-ts"], box.posts[1]["x-hm-ts"])
        assertEquals("POST\n/v1/pair\n${box.posts[0]["x-hm-ts"]}\n${hex(body)}", signed[0])
    }
}
