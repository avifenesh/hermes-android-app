package dev.avifenesh.hermes

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** One immutable, user-reviewed body. A retry reuses this id; a changed body gets a new id. */
internal data class PublisherReportDraft(val text: String, val id: String = UUID.randomUUID().toString()) {
    init {
        val uuid = UUID.fromString(id)
        require(uuid.toString() == id && uuid.version() == 4 && uuid.variant() == 2)
        require(text.toByteArray(Charsets.UTF_8).size <= SupportPayload.BODY_UTF8_LIMIT)
    }
}

/** No gateway client, preferences, signing keys, cookies, redirects, retries or background work. */
internal object PublisherReports {
    const val ENDPOINT = "https://hermes-android-reports.aviarchi1994.workers.dev/v1/reports"
    private const val ACK_BYTES_LIMIT = 4096L

    // Deliberately constructed from scratch, never Api's authenticated client/newBuilder().
    internal fun newClient(): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val client by lazy { newClient() }

    internal fun request(draft: PublisherReportDraft): Request {
        // Bound decoded text, not the JSON envelope: escaping can expand an 8 KiB body.
        val json = JSONObject().put("id", draft.id).put("text", draft.text).toString()
        val encoded = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        // Also forbid HTTP follow-up replays (e.g. 503 + Retry-After: 0), not just
        // connection retries. A human retry creates a fresh Call with the same draft.
        val oneShot = object : RequestBody() {
            override fun contentType() = encoded.contentType()
            override fun contentLength() = encoded.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) = encoded.writeTo(sink)
        }
        return Request.Builder().url(ENDPOINT)
            .post(oneShot).build()
    }

    suspend fun send(draft: PublisherReportDraft) = sendUsing(client, request(draft), draft.id)

    /** Test seam accepts synthetic requests only in tests; production always uses ENDPOINT above. */
    internal suspend fun sendUsing(client: OkHttpClient, request: Request, expectedId: String) {
        currentCoroutineContext().ensureActive()
        client.newCall(request).awaitBody { response ->
            if (response.code != 200 && response.code != 202) throw IOException("Report not acknowledged")
            val source = response.body.source()
            if (source.request(ACK_BYTES_LIMIT + 1)) throw IOException("Report acknowledgement too large")
            val ack = try { JSONObject(source.readUtf8()) } catch (_: Exception) {
                throw IOException("Invalid report acknowledgement")
            }
            // Do not coerce strings/numbers to booleans, or accept an unrelated receipt.
            if (ack.opt("received") != true || ack.opt("id") != expectedId)
                throw IOException("Report not acknowledged")
        }
        currentCoroutineContext().ensureActive()
    }
}
