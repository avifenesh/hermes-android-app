package dev.avifenesh.hermes

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only real HTTP regression: two random-port IPv4 loopback origins, synthetic secrets only.
 * Launch ONLY on a disposable emulator with freshly cleared app data; no endpoint/secret extras.
 * Default mode exercises actual Api calls. --ez baseline true reproduces the old redirect policy
 * using LOCAL client copies (never mutates Api clients) to demonstrate header/body exfiltration.
 * Baseline is a vulnerability reproduction, NOT a security pass. No release policy switches exist.
 * Read files/redirect-isolation-report.json via run-as; parent owns debug manifest registration.
 */
class RedirectIsolationProbeActivity : FragmentActivity() {
    private val cfId = "synthetic-redirect-cf-id"
    private val cfSecret = "synthetic-redirect-cf-secret"
    private val token = "synthetic-redirect-bearer"
    private val pairCode = "synthetic-redirect-pair-code"
    private val payload = JSONObject().put("message", "synthetic-private-body")
    private val json = "application/json".toMediaType()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this).apply { text = "Redirect isolation probe running"; textSize = 20f }
        setContentView(label)
        if (!Build.PRODUCT.contains("sdk") || getSharedPreferences("hm", MODE_PRIVATE).all.isNotEmpty()) {
            label.text = "REFUSED: use a freshly cleared disposable Android emulator"
            return
        }
        val baseline = intent.getBooleanExtra("baseline", false)
        lifecycleScope.launch {
            // App's first onActivityStarted must see unpaired production preferences.
            delay(50)
            val report = JSONObject().put("mode", if (baseline) "unsafe-baseline" else "fixed")
                .put("passed", false).put("baselineExposureConfirmed", false)
            val checks = JSONArray()
            report.put("checks", checks)
            try {
                withContext(Dispatchers.IO) { runProbe(baseline, checks) }
                if (baseline) report.put("baselineExposureConfirmed", true)
                else report.put("passed", true)
                label.text = if (baseline) "BASELINE EXPOSURE CONFIRMED (not a security pass)"
                    else "REDIRECT ISOLATION PASS (${checks.length()} checks)"
            } catch (e: CancellationException) {
                report.put("error", "Cancelled")
                throw e
            } catch (e: Exception) {
                report.put("error", e.javaClass.simpleName).put("detail", e.message)
                label.text = "REDIRECT PROBE FAIL: ${e.message}"
            } finally {
                // Reports contain counts and booleans, never captured headers or bodies.
                File(filesDir, "redirect-isolation-report.json").writeText(report.toString(2))
                Log.i("RedirectIsolationProbe", report.toString())
            }
        }
    }

    private suspend fun runProbe(baseline: Boolean, checks: JSONArray) {
        val isolated = object : ContextWrapper(this@RedirectIsolationProbeActivity) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("redirect_probe_$name", mode)
        }
        val testPrefs = isolated.getSharedPreferences("hm", Context.MODE_PRIVATE)
        check(testPrefs.edit().clear().commit())
        val dest = File(cacheDir, "redirect-probe-download.bin")
        try {
            Prefs.init(isolated)
            Keys.ensure()
            Prefs.token = token
            Prefs.cfId = cfId
            Prefs.cfSecret = cfSecret
            Api.epoch.invalidate()
            Fixture().use { sink ->
                Fixture(sink.url + "/capture").use { source ->
                    Prefs.base = source.url
                    // Prove both listeners work; an unreachable sink must not masquerade as isolation.
                    Api.plain.newCall(Request.Builder().url(sink.url + "/health").build()).execute().use {
                        check(it.code == 200) { "Sink health failed" }
                    }
                    check(sink.connections.get() == 1 && sink.requests.size == 1)
                    check(Api.get("/v1/probe").getBoolean("ok")) { "Signed source health failed" }
                    assertCredentials(source.requests.last(), signed = true)
                    checks.put(JSONObject().put("name", "two-origin-positive-control").put("passed", true))
                    if (!baseline) {
                        val clients = mapOf("http" to Api.http, "plain" to Api.plain, "sse" to Api.sse,
                            "download-derived" to Api.http.newBuilder().readTimeout(5, TimeUnit.MINUTES).build())
                        clients.forEach { (name, client) ->
                            check(!client.followRedirects && !client.followSslRedirects) { "$name follows redirects" }
                            checks.put(JSONObject().put("name", "$name-policy").put("passed", true))
                        }
                    }
                    // Debug copies reproduce the pre-fix defaults; neither is installed in Api.
                    val oldHttp = if (baseline) Api.http.newBuilder().followRedirects(true).followSslRedirects(true).build() else null
                    val oldPlain = if (baseline) Api.plain.newBuilder().followRedirects(true).followSslRedirects(true).build() else null
                    var leakedBodies = 0
                    for (status in listOf(301, 302, 303, 307, 308)) {
                        source.status.set(status)
                        val kinds = if (baseline) listOf("signed-get", "signed-post", "pair-plain")
                            else listOf("signed-get", "signed-post", "pair-plain", "sse", "download")
                        for (kind in kinds) {
                            val id = "$status/$kind"
                            val sourceBefore = source.connections.get()
                            val sinkBefore = sink.connections.get()
                            if (baseline) {
                                val client: OkHttpClient
                                val request: Request
                                if (kind == "pair-plain") {
                                    client = oldPlain!!
                                    // Same sensitive pairing fields, without a successful Api.pair changing state.
                                    val body = JSONObject().put("code", pairCode).put("device_name", "probe")
                                        .put("pub", Keys.pub(Keys.REQ)).put("apub", Keys.pub(Keys.APR)).put("lock", !Prefs.noLock)
                                    request = Request.Builder().url(source.url + "/v1/pair")
                                        .header("CF-Access-Client-Id", cfId).header("CF-Access-Client-Secret", cfSecret)
                                        .post(body.toString().toRequestBody(json)).build()
                                } else {
                                    client = oldHttp!!
                                    request = Api.request("/v1/probe").apply {
                                        if (kind == "signed-post") post(payload.toString().toRequestBody(json))
                                    }.build()
                                }
                                client.newCall(request).execute().use {
                                    check(it.code == 200) { "$id baseline did not redirect" }
                                }
                                check(sink.connections.get() == sinkBefore + 1) { "$id baseline missed sink" }
                                val stolen = sink.requests.last()
                                check(stolen.headers["cf-access-client-id"] == cfId &&
                                    stolen.headers["cf-access-client-secret"] == cfSecret) { "$id baseline CF leak absent" }
                                check(stolen.headers["authorization"] == null) { "$id unexpected cross-origin bearer" }
                                if (kind != "pair-plain") check(!stolen.headers["x-hm-sig"].isNullOrBlank()) {
                                    "$id baseline signature leak absent"
                                }
                                val bodyLeaked = stolen.body.isNotEmpty()
                                if (kind != "signed-get" && status in listOf(307, 308)) {
                                    check(stolen.body == source.requests.last().body) { "$id baseline body leak absent" }
                                    check(bodyLeaked) { "$id empty baseline body" }
                                    leakedBodies++
                                }
                                checks.put(JSONObject().put("name", id).put("sinkConnections", 1)
                                    .put("cfLeaked", true).put("bodyLeaked", bodyLeaked))
                            } else {
                                var error: ApiError? = null
                                try {
                                    when (kind) {
                                        "signed-get" -> Api.get("/v1/probe")
                                        "signed-post" -> Api.post("/v1/probe", payload)
                                        "pair-plain" -> Api.pair(Uri.Builder().scheme("hermes").authority("pair")
                                            .appendQueryParameter("u", source.url).appendQueryParameter("c", pairCode)
                                            .appendQueryParameter("i", cfId).appendQueryParameter("s", cfSecret).build())
                                        "sse" -> Api.sse.newCall(Api.request("/v1/events").build()).execute().use {
                                            check(it.code == status && !it.isSuccessful) { "$id wrong SSE status" }
                                        }
                                        "download" -> {
                                            dest.writeText("existing-destination")
                                            Api.download("/v1/media/probe", dest, null) {}
                                        }
                                    }
                                } catch (e: ApiError) { error = e }
                                if (kind != "sse") {
                                    check(error?.code == status) { "$id missing original HTTP ApiError" }
                                    val expected = if (kind == "download") "HTTP $status" else "probe redirect"
                                    check(error?.message == expected) { "$id changed API error message" }
                                }
                                check(sink.connections.get() == sinkBefore) { "$id reached second origin" }
                                if (kind == "download") check(dest.readText() == "existing-destination") { "$id published redirect body" }
                                check(Prefs.base == source.url && Prefs.token == token && Prefs.cfId == cfId && Prefs.cfSecret == cfSecret) {
                                    "$id changed pairing identity"
                                }
                                checks.put(JSONObject().put("name", id).put("passed", true).put("sinkConnections", 0))
                            }
                            check(source.connections.get() == sourceBefore + 1) { "$id did not reach source exactly once" }
                            val sent = source.requests.last()
                            assertCredentials(sent, signed = kind != "pair-plain")
                            if (kind == "pair-plain") check(JSONObject(sent.body).getString("code") == pairCode) { "$id missing pairing body" }
                            if (kind == "signed-post") check(sent.body == payload.toString()) { "$id missing signed body" }
                            source.assertHealthy()
                            sink.assertHealthy()
                        }
                    }
                    if (baseline) check(leakedBodies == 4) { "Baseline did not reproduce all 307/308 body leaks" }
                    else check(sink.connections.get() == 1) { "Unexpected second-origin traffic" }
                }
            }
        } finally {
            Hub.disconnect()
            Api.epoch.invalidate()
            Prefs.init(this@RedirectIsolationProbeActivity)
            testPrefs.edit().clear().commit()
            dest.delete()
        }
    }

    private fun assertCredentials(request: Captured, signed: Boolean) {
        check(request.headers["cf-access-client-id"] == cfId && request.headers["cf-access-client-secret"] == cfSecret) {
            "Source did not receive synthetic CF credentials"
        }
        if (signed) check(request.headers["authorization"] == "Bearer $token" &&
            !request.headers["x-hm-sig"].isNullOrBlank() && !request.headers["x-hm-ts"].isNullOrBlank()) {
            "Source did not receive signed synthetic request"
        } else check(request.headers["authorization"] == null) { "Pairing unexpectedly used a bearer" }
    }

    private data class Captured(val headers: Map<String, String>, val body: String)

    /** Raw HTTP fixtures avoid mocks/interceptors and count TCP accepts, not merely parsed requests. */
    private class Fixture(private val location: String? = null) : Closeable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}"
        val status = AtomicInteger(200)
        val connections = AtomicInteger()
        val requests: MutableList<Captured> = Collections.synchronizedList(mutableListOf())
        private val failure = AtomicReference<Throwable?>(null)
        private val thread = Thread({
            try {
                while (!listener.isClosed) listener.accept().use { socket ->
                    connections.incrementAndGet()
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream()
                    val requestLine = line(input)
                    check(requestLine.endsWith(" HTTP/1.1")) { "Invalid HTTP request line" }
                    val headers = mutableMapOf<String, String>()
                    var header = line(input)
                    while (header.isNotEmpty()) {
                        val colon = header.indexOf(':')
                        check(colon > 0 && headers.size < 100) { "Invalid HTTP headers" }
                        headers[header.substring(0, colon).lowercase(Locale.ROOT)] = header.substring(colon + 1).trim()
                        header = line(input)
                    }
                    check(headers["transfer-encoding"] == null) { "Unexpected streaming request body" }
                    val length = headers["content-length"]?.toInt() ?: 0
                    check(length in 0..65_536) { "Invalid HTTP body length" }
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val n = input.read(body, offset, length - offset)
                        check(n > 0) { "Truncated HTTP request body" }
                        offset += n
                    }
                    requests.add(Captured(headers, body.toString(Charsets.UTF_8)))
                    val code = status.get()
                    val response = if (code == 200) "{\"ok\":true}" else "{\"error\":\"probe redirect\"}"
                    val redirect = if (code != 200) "Location: ${checkNotNull(location)}\r\n" else ""
                    val bytes = response.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 $code Probe\r\n${redirect}Content-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        write(bytes)
                        flush()
                    }
                }
            } catch (e: SocketException) {
                if (!listener.isClosed) failure.set(e)
            } catch (e: Exception) { failure.set(e) }
        }, "redirect-probe-loopback").apply { isDaemon = true; start() }

        fun assertHealthy() { check(failure.get() == null) { "Loopback fixture failed: ${failure.get()?.javaClass?.simpleName}" } }
        override fun close() {
            listener.close()
            thread.join(6_000)
            check(!thread.isAlive) { "Loopback fixture did not stop" }
            assertHealthy()
        }

        private fun line(input: InputStream): String {
            val out = StringBuilder()
            while (out.length < 16_384) {
                val b = input.read()
                check(b >= 0) { "Unexpected HTTP EOF" }
                if (b == 10) return out.toString().removeSuffix("\r")
                out.append(b.toChar())
            }
            error("HTTP line too long")
        }
    }
}
