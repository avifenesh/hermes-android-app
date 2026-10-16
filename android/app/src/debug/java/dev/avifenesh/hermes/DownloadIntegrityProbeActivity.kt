package dev.avifenesh.hermes

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only, self-contained HTTP integration probe. Use a disposable, freshly cleared emulator.
 * No URL/token extras: the only endpoint is an in-process IPv4 loopback server on a random port.
 * Parent registers this activity in the debug manifest; never register it in main/release.
 * Read files/download-integrity-report.json using run-as after the screen reports completion.
 */
class DownloadIntegrityProbeActivity : FragmentActivity() {
    private data class Case(
        val name: String,
        val body: String = "data",
        val contentLength: Long? = 4,
        val expected: Long? = 4,
        val failure: String? = null,
        val legacyCall: Boolean = false,
        val chunked: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this).apply { text = "Download integrity probe running"; textSize = 20f }
        setContentView(label)
        // Refuse to replace an existing pairing or run with any real credentials. App.onCreate
        // precedes this gate: launch ONLY on a disposable emulator with freshly cleared app data.
        if (!Build.PRODUCT.contains("sdk") || getSharedPreferences("hm", MODE_PRIVATE).all.isNotEmpty()) {
            label.text = "REFUSED: use a freshly cleared disposable Android emulator"
            return
        }
        lifecycleScope.launch {
            // Let App's initial onActivityStarted observe the unpaired preferences first.
            delay(50)
            val report = JSONObject().put("passed", false)
            try {
                val results = withContext(Dispatchers.IO) { runProbe() }
                report.put("passed", true).put("checks", results)
                label.text = "DOWNLOAD INTEGRITY PASS (${results.length()} checks)"
            } catch (e: CancellationException) {
                report.put("error", "Cancelled")
                throw e
            } catch (e: Exception) {
                // Do not log request headers, preferences, or credentials, even on failure.
                report.put("error", e.javaClass.simpleName).put("detail", e.message)
                label.text = "DOWNLOAD INTEGRITY FAIL: ${e.message}"
            } finally {
                File(filesDir, "download-integrity-report.json").writeText(report.toString(2))
                Log.i("DownloadIntegrityProbe", report.toString())
            }
        }
    }

    private suspend fun runProbe(): JSONArray {
        val root = File(cacheDir, "download-integrity-probe").apply { mkdirs() }
        val isolated = object : ContextWrapper(this@DownloadIntegrityProbeActivity) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("download_probe_$name", mode)
            override fun getCacheDir(): File = root
        }
        val testPrefs = isolated.getSharedPreferences("hm", Context.MODE_PRIVATE)
        check(testPrefs.edit().clear().commit())
        val cases = listOf(
            Case("exact"),
            Case("absent-metadata", expected = null),
            Case("legacy-progress-call", expected = null, legacyCall = true),
            Case("negative-metadata", expected = -1),
            Case("zero", body = "", contentLength = 0, expected = 0),
            Case("metadata-larger-than-http", expected = 5, failure = "metadata"),
            Case("metadata-smaller-than-http", expected = 3, failure = "metadata"),
            Case("unknown-http-exact", contentLength = null),
            Case("unknown-http-absent-metadata", contentLength = null, expected = null),
            Case("unknown-http-short", contentLength = null, expected = 5, failure = "metadata"),
            Case("unknown-http-long", contentLength = null, expected = 3, failure = "metadata"),
            Case("chunked-exact", contentLength = null, chunked = true),
            Case("chunked-short", contentLength = null, expected = 5, chunked = true, failure = "metadata"),
            Case("chunked-long", contentLength = null, expected = 3, chunked = true, failure = "metadata"),
            Case("http-truncated", contentLength = 8, failure = "transport"),
            // Bytes beyond HTTP framing aren't part of the body: metadata still rejects the short result.
            Case("http-understated", contentLength = 2, failure = "metadata")
        )
        val current = AtomicReference(cases.first())
        val serverFailure = AtomicReference<Throwable?>(null)
        val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val serving = Thread({
            try {
                while (!listener.isClosed) {
                    listener.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val requestLine = reader.readLine().orEmpty()
                        while (!reader.readLine().isNullOrEmpty()) { /* consume signed headers, never log */ }
                        val case = current.get()
                        val out = socket.getOutputStream()
                        if (!requestLine.startsWith("GET /v1/media/probe ")) {
                            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        } else {
                            val framing = if (case.chunked) "Transfer-Encoding: chunked\r\n"
                                else case.contentLength?.let { "Content-Length: $it\r\n" }.orEmpty()
                            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                                framing + "Connection: close\r\n\r\n").toByteArray())
                            if (case.chunked) {
                                out.write(("${case.body.toByteArray().size.toString(16)}\r\n${case.body}\r\n0\r\n\r\n").toByteArray())
                            } else out.write(case.body.toByteArray())
                        }
                        out.flush()
                    }
                }
            } catch (e: SocketException) {
                if (!listener.isClosed) serverFailure.set(e)
            } catch (e: Exception) { serverFailure.set(e) }
        }, "download-integrity-loopback").apply { isDaemon = true; start() }
        val results = JSONArray()
        try {
            Prefs.init(isolated)
            Keys.ensure()
            // Ephemeral test credentials only. The signer and Android Keystore path remain real.
            Prefs.token = "download-probe-${java.util.UUID.randomUUID()}"
            Prefs.base = "http://127.0.0.1:${listener.localPort}"
            Api.epoch.invalidate()
            for (case in cases) {
                current.set(case)
                for (existing in listOf(false, true)) {
                    val dest = File(root, "attachment.bin")
                    dest.delete()
                    if (existing) dest.writeText("existing-destination")
                    var failure: Exception? = null
                    try {
                        if (case.legacyCall) Api.download("/v1/media/probe", dest) {}
                        else Api.download("/v1/media/probe", dest, case.expected) {}
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failure = e }
                    val id = "${case.name}/${if (existing) "existing" else "new"}"
                    if (case.failure == null) {
                        check(failure == null) { "$id unexpectedly failed: ${failure?.javaClass?.simpleName}" }
                        check(dest.readText() == case.body) { "$id wrong published contents" }
                    } else {
                        check(failure != null) { "$id accepted invalid download" }
                        if (case.failure == "metadata") check(failure is ApiError &&
                            failure.message.orEmpty().startsWith("Download size mismatch:")) { "$id wrong failure" }
                        if (case.failure == "transport") check(failure is java.io.IOException) { "$id wrong transport failure" }
                        if (existing) check(dest.readText() == "existing-destination") { "$id overwrote destination" }
                        else check(!dest.exists()) { "$id published invalid download" }
                    }
                    check(root.walkTopDown().none { it.name.endsWith(".part") }) { "$id left a partial file" }
                    check(serverFailure.get() == null) { "$id loopback server failed" }
                    results.put(id)
                }
            }
            // Exercise the actual metadata caller, including cache misses and a stale cached file.
            current.set(Case("fetch-invalid", expected = 5))
            val cached = File(mediaDir(isolated), "probe_attachment.bin")
            for (existing in listOf(false, true)) {
                cached.delete()
                if (existing) cached.writeText("stale") // size differs from expected 6
                var rejected = false
                try { fetchMedia(isolated, "probe", "attachment.bin", 6) }
                catch (e: ApiError) { rejected = e.message.orEmpty().startsWith("Download size mismatch:") }
                check(rejected) { "fetchMedia failed to forward metadata size" }
                check(if (existing) cached.readText() == "stale" else !cached.exists()) { "fetchMedia published invalid data" }
                check(root.walkTopDown().none { it.name.endsWith(".part") }) { "fetchMedia left partial files" }
                results.put("fetchMedia-invalid/${if (existing) "existing" else "new"}")
            }
            current.set(Case("fetch-valid"))
            check(fetchMedia(isolated, "probe", "attachment.bin", 4).readText() == "data")
            // A valid cache hit must avoid HTTP; this response would fail if fetched again.
            current.set(Case("must-not-fetch", body = "bad", contentLength = 3))
            check(fetchMedia(isolated, "probe", "attachment.bin", 4).readText() == "data")
            check(root.walkTopDown().none { it.name.endsWith(".part") })
            results.put("fetchMedia-valid-and-cache-hit")
            return results
        } finally {
            Hub.disconnect()
            Api.epoch.invalidate()
            Prefs.init(this@DownloadIntegrityProbeActivity)
            testPrefs.edit().clear().commit()
            listener.close()
            serving.join(6_000)
            root.deleteRecursively()
        }
    }
}
