package dev.avifenesh.hermes

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Android JSON implementation; synthetic loopback only. Also runnable with org.json on a JVM. */
class PublisherReportsTest {
    /** Explicit opt-in release-preparation probe; never runs against production in an ordinary suite. */
    @Test fun optionalLiveReceiverWithSyntheticTextOnly() = runBlocking {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("publisher_live_check") == "synthetic-only")
        val draft = PublisherReportDraft("SYNTHETIC ANDROID TRANSPORT CHECK: no chats, files, tokens or user data.")
        println("SYNTHETIC_PUBLISHER_REPORT_ID=" + draft.id)
        PublisherReports.send(draft)
    }

    private class Server(val reply: String?) : AutoCloseable {
        val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val connected = CountDownLatch(1)
        @Volatile var socket: Socket? = null
        private val worker = thread(isDaemon = true, name = "publisher-report-test") {
            try {
                while (!listener.isClosed) listener.accept().use { s ->
                    socket = s
                    val input = s.getInputStream()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val next = input.read()
                        if (next == -1) break
                        header.append(next.toChar())
                    }
                    val length = Regex("(?i)Content-Length: (\\d+)").find(header)?.groupValues?.get(1)?.toInt() ?: 0
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val n = input.read(body, offset, length - offset)
                        if (n < 0) break
                        offset += n
                    }
                    requests.add(header.toString() to body.toString(Charsets.UTF_8))
                    connected.countDown()
                    if (reply != null) {
                        s.getOutputStream().apply { write(reply.toByteArray()); flush() }
                    } else while (input.read() != -1) { }
                }
            } catch (_: Exception) { }
        }
        val url get() = "http://127.0.0.1:${listener.localPort}/v1/reports"
        override fun close() { socket?.close(); listener.close(); worker.join(2000) }
    }

    private fun response(code: Int, body: String, extra: String = "") =
        "HTTP/1.1 $code Test\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n$extra\r\n$body"

    private fun shutdown(client: OkHttpClient) {
        client.dispatcher.cancelAll(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
    }

    private suspend fun send(client: OkHttpClient, server: Server, draft: PublisherReportDraft) {
        val synthetic = PublisherReports.request(draft).newBuilder().url(server.url).build()
        PublisherReports.sendUsing(client, synthetic, draft.id)
    }

    @Test fun draftConstructionAndPreviewDoNotSend() {
        val draft = PublisherReportDraft("reviewed only")
        val request = PublisherReports.request(draft)
        assertEquals(PublisherReports.ENDPOINT, request.url.toString())
        assertEquals(4, UUID.fromString(draft.id).version())
        assertEquals(2, UUID.fromString(draft.id).variant())
        assertEquals(0, request.headers.size)
        assertTrue(request.body!!.isOneShot())
        // No Call is created/enqueued by draft or request construction. send() is explicit.
        val buffer = Buffer(); request.body!!.writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        assertEquals(setOf("id", "text"), json.keys().asSequence().toSet())
        assertEquals(draft.text, json.getString("text"))
        assertEquals(draft.id, json.getString("id"))
    }

    @Test fun byteLimitAppliesToTextNotExpandedJson() {
        val text = "\u0001".repeat(SupportPayload.BODY_UTF8_LIMIT)
        val request = PublisherReports.request(PublisherReportDraft(text))
        assertTrue(request.body!!.contentLength() > SupportPayload.BODY_UTF8_LIMIT)
        try { PublisherReportDraft(text + "a"); fail("Oversized text accepted") } catch (_: IllegalArgumentException) { }
        val unicode = "😀".repeat(2048)
        assertEquals(8192, unicode.toByteArray(Charsets.UTF_8).size)
        PublisherReportDraft(unicode)
        try { PublisherReportDraft(unicode + "a"); fail("Oversized UTF-8 accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun clientIsUnsignedCookieFreeAndBounded() {
        val client = PublisherReports.newClient()
        try {
            assertTrue(client.interceptors.isEmpty()); assertTrue(client.networkInterceptors.isEmpty())
            assertFalse(client.followRedirects); assertFalse(client.followSslRedirects)
            assertFalse(client.retryOnConnectionFailure)
            assertEquals(okhttp3.CookieJar.NO_COOKIES, client.cookieJar)
            assertEquals(okhttp3.Authenticator.NONE, client.authenticator)
            assertEquals(okhttp3.Authenticator.NONE, client.proxyAuthenticator)
            assertEquals(20000, client.callTimeoutMillis)
        } finally { shutdown(client) }
    }

    @Test fun onlyMatchingBooleanReceiptsWith200Or202Succeed() = runBlocking {
        val draft = PublisherReportDraft("עברית 😀\n&?= user-reviewed")
        val client = PublisherReports.newClient()
        try {
            for (code in listOf(200, 202)) Server(response(code, "{\"received\":true,\"id\":\"${draft.id}\"}")).use { server ->
                send(client, server, draft)
                val (headers, text) = server.requests.single()
                assertEquals(draft.text, JSONObject(text).getString("text"))
                assertEquals(draft.id, JSONObject(text).getString("id"))
                for (secretHeader in listOf("Authorization", "Cookie", "CF-Access", "X-HM-", "X-Device", "X-Signature"))
                    assertFalse("Unexpected credential header: $headers", headers.contains(secretHeader, ignoreCase = true))
            }
            val bad = listOf(
                201 to "{\"received\":true,\"id\":\"${draft.id}\"}",
                200 to "{\"received\":\"true\",\"id\":\"${draft.id}\"}",
                202 to "{\"received\":false,\"id\":\"${draft.id}\"}",
                202 to "{\"received\":true,\"id\":\"wrong\"}",
                200 to "{}", 200 to "invalid", 200 to " ".repeat(4097)
            )
            for ((code, ack) in bad) Server(response(code, ack)).use { server ->
                try { send(client, server, draft); fail("Accepted $code / $ack") } catch (_: IOException) { }
            }
        } finally { shutdown(client) }
    }

    @Test fun redirectsAnd503DoNotReplayAndHumanRetriesReuseExactDraft() = runBlocking {
        val draft = PublisherReportDraft("unchanged")
        val client = PublisherReports.newClient()
        try {
            Server(response(302, "", "Location: http://127.0.0.1:1/never\r\n")).use { server ->
                try { send(client, server, draft); fail("Redirect accepted") } catch (_: IOException) { }
                assertEquals(1, server.requests.size)
            }
            Server(response(503, "", "Retry-After: 0\r\n")).use { server ->
                repeat(2) {
                    try { send(client, server, draft); fail("503 accepted") } catch (_: IOException) { }
                }
                assertEquals(2, server.requests.size) // exactly one for each explicit call
                assertEquals(server.requests[0].second, server.requests[1].second)
            }
            assertNotEquals(draft.id, PublisherReportDraft("edited").id)
        } finally { shutdown(client) }
    }

    @Test fun cancellationClosesPendingCallAndDoesNotRetry() = runBlocking {
        val client = PublisherReports.newClient()
        try {
            Server(null).use { server ->
                val job = async(start = CoroutineStart.UNDISPATCHED) { send(client, server, PublisherReportDraft("cancel")) }
                assertTrue(server.connected.await(3, TimeUnit.SECONDS))
                withTimeout(1000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                assertEquals(1, server.requests.size)
            }
        } finally { shutdown(client) }
    }
}
