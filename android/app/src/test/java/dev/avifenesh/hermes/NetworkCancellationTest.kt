package dev.avifenesh.hermes

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Real loopback sockets, with no gateway, Android runtime, or production credentials. */
class NetworkCancellationTest {
    private class Server(private val reply: String?) : AutoCloseable {
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val connected = CountDownLatch(1)
        val sent = CountDownLatch(1)
        @Volatile var socket: Socket? = null
        private val worker = thread(isDaemon = true, name = "net-regression-server") {
            try {
                listener.accept().use { s ->
                    socket = s
                    val reader = s.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    connected.countDown()
                    if (reply != null) {
                        s.getOutputStream().apply { write(reply.toByteArray()); flush() }
                    }
                    sent.countDown()
                    // Hold the response open until cancellation closes the client connection.
                    while (s.getInputStream().read() != -1) { }
                }
            } catch (_: Exception) { }
        }
        val url get() = "http://127.0.0.1:${listener.localPort}/test"
        override fun close() {
            socket?.close(); listener.close(); worker.join(2000)
        }
    }

    private fun client() = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
    private fun shutdown(c: OkHttpClient) { c.dispatcher.cancelAll(); c.dispatcher.executorService.shutdownNow(); c.connectionPool.evictAll() }

    @Test fun cancelsTheCallWhileWaitingForHeaders() = runBlocking {
        Server(null).use { server ->
            val client = client()
            try {
                val call = client.newCall(Request.Builder().url(server.url).build())
                val result = async(start = CoroutineStart.UNDISPATCHED) { call.awaitBody { it.body.string() } }
                assertTrue(server.connected.await(3, TimeUnit.SECONDS))
                withTimeout(1000) { result.cancelAndJoin() }
                assertTrue("Coroutine cancellation must cancel the underlying socket", call.isCanceled())
            } finally { shutdown(client) }
        }
    }

    @Test fun cancellationStaysAttachedWhileReadingPartialBody() = runBlocking {
        Server("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\na").use { server ->
            val client = client()
            val reading = CountDownLatch(1)
            try {
                val call = client.newCall(Request.Builder().url(server.url).build())
                val result = async(start = CoroutineStart.UNDISPATCHED) {
                    call.awaitBody { reading.countDown(); it.body.string() }
                }
                assertTrue(reading.await(3, TimeUnit.SECONDS))
                withTimeout(1000) { result.cancelAndJoin() }
                assertTrue("Cancellation after headers must still close the call", call.isCanceled())
            } finally { shutdown(client) }
        }
    }

    @Test fun readsCompleteResponseAndPropagatesBodyErrors() = runBlocking {
        Server("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok").use { server ->
            val client = client()
            try {
                val call = client.newCall(Request.Builder().url(server.url).build())
                assertEquals("ok", withTimeout(3000) { call.awaitBody { it.body.string() } })
            } finally { shutdown(client) }
        }
        Server("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n").use { server ->
            val client = client()
            try {
                val call = client.newCall(Request.Builder().url(server.url).build())
                try {
                    withTimeout(3000) { call.awaitBody<String> { throw IllegalArgumentException("bad body") } }
                    fail("Body parser errors must reach the caller")
                } catch (e: IllegalArgumentException) { assertEquals("bad body", e.message) }
            } finally { shutdown(client) }
        }
    }
}
