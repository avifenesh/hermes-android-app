package dev.avifenesh.hermes

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.RequestBody
import okio.BufferedSink
import okio.ForwardingSink
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import java.io.File
import java.io.InputStream
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit

fun JSONObject.str(k: String, d: String = ""): String = if (isNull(k)) d else optString(k, d)

object Prefs {
    private lateinit var sp: SharedPreferences
    @Synchronized fun init(c: Context) {
        sp = c.getSharedPreferences("hm", Context.MODE_PRIVATE)
        WakePreferences.migrate(sp)
        // App.onCreate migrates without requiring the machine picker. Keep unreadable
        // storage intact; the picker reports failure and mutations still fail closed.
        if (sp.contains("machines")) runCatching { sec("machines", strict = true) }
    }
    private fun g(k: String): String? = sp.getString(k, null)
    private fun s(k: String, v: String?) { sp.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply() }
    var base: String? get() = g("base"); set(v) = s("base", v)
    var token: String? get() = sec("token"); set(v) = setSec("token", v)
    var deviceId: String? get() = g("device"); set(v) = s("device", v)
    var cfId: String? get() = g("cfi"); set(v) = s("cfi", v)
    var cfSecret: String? get() = sec("cfs"); set(v) = setSec("cfs", v)
    var server: String? get() = g("server"); set(v) = s("server", v)
    var profile: String? get() = g("profile"); set(v) = s("profile", v)
    var fcmCfg: String? get() = g("fcmcfg"); set(v) = s("fcmcfg", v)
    var fcmToken: String? get() = g("fcmtok"); set(v) = s("fcmtok", v)
    var seq: Long get() = sp.getLong("seq", 0); set(v) { sp.edit().putLong("seq", v).apply() }
    var nseq: Long get() = sp.getLong("nseq", 0); set(v) { sp.edit().putLong("nseq", v).apply() }
    var theme: Int get() = sp.getInt("theme", 0); set(v) { sp.edit().putInt("theme", v).apply() }
    var phoneArmed: Boolean get() = sp.getBoolean("phonearmed", false); set(v) { sp.edit().putBoolean("phonearmed", v).apply(); PhoneService.onArmChanged(); PhoneService.controlState.value = v }
    var live: Boolean get() = sp.getBoolean("live", true); set(v) { sp.edit().putBoolean("live", v).apply() }
    var fleetSeen: Double get() = g("fleetseen")?.toDoubleOrNull() ?: 0.0; set(v) = s("fleetseen", v.toString())
    var lastProfile: String? get() = g("lastprof"); set(v) = s("lastprof", v)
    var accent: Int get() = sp.getInt("accent", 0); set(v) { sp.edit().putInt("accent", v).apply() }
    var talkChat: String? get() = g("talkchat"); set(v) = s("talkchat", v)
    var agentName: String get() = g("agent")?.takeIf { it.isNotBlank() } ?: "Hermes"; set(v) = s("agent", v)
    var noLock: Boolean get() = sp.getBoolean("nolock", false); set(v) { sp.edit().putBoolean("nolock", v).apply() }
    var serverVoice: Boolean get() = sp.getBoolean("servervoice", true); set(v) { sp.edit().putBoolean("servervoice", v).apply() }
    var wakeWord: Boolean get() = sp.getBoolean("wakeword", false); set(v) { sp.edit().putBoolean("wakeword", v).apply() }
    var wakePhrase: String get() = g("wakephrase")?.takeIf { p -> WakeWord.PHRASES.any { it.first == p } } ?: WakeWord.PHRASES.first().first; set(v) = s("wakephrase", v)
    var wakeThreshold: Float
        get() = WakePreferences.cutoff(sp)
        set(v) {
            require(v.isFinite() && v in 0.01f..0.99f)
            WakePreferences.migrate(sp)
            sp.edit().putFloat(WakePreferences.CUTOFF_KEY, v).apply()
        }
    val paired: Boolean get() = token != null && base != null
    fun clear() { sp.edit().clear().apply() }

    // Bearer tokens, CF Access secrets, and the entire parked-machine registry use the same
    // non-exportable AndroidKeyStore AES-GCM key. The registry migrates during init.
    internal var machines: String? get() = sec("machines", strict = true); set(v) = setSec("machines", v)
    private const val SEC_KEY = "hm_prefs"
    private fun aes(): javax.crypto.SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(SEC_KEY, null) as? javax.crypto.SecretKey)?.let { return it }
        val g = javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(SEC_KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }
    @Synchronized private fun setSec(k: String, v: String?) {
        // Encrypt before editing so an encryption failure leaves the old value untouched.
        val encrypted = v?.let {
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, aes()) }
            Base64.encodeToString(c.iv + c.doFinal(it.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }
        val edit = sp.edit().remove(k)
        if (encrypted == null) edit.remove("e_$k") else edit.putString("e_$k", encrypted)
        // One transaction: never persist ciphertext and plaintext in separate writes.
        if (k == "machines") check(edit.commit()) { "Couldn't save paired machines" } else edit.apply()
    }
    @Synchronized private fun sec(k: String, strict: Boolean = false): String? {
        g("e_$k")?.let { e ->
            val decoded = runCatching {
                val b = Base64.decode(e, Base64.NO_WRAP)
                val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                c.init(javax.crypto.Cipher.DECRYPT_MODE, aes(), javax.crypto.spec.GCMParameterSpec(128, b, 0, 12))
                String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
            }
            // A broken registry must not look empty and get overwritten by park/forget.
            val value = if (strict) decoded.getOrThrow() else decoded.getOrNull()
            if (strict && sp.contains(k)) {
                // Recover a mixed legacy/encrypted state only after authenticating the ciphertext.
                check(sp.edit().remove(k).commit()) { "Couldn't remove legacy paired machines" }
            }
            return value
        }
        return g(k)?.also { setSec(k, it) }
    }
}

/** Two hardware-backed P-256 keys: hm_req signs every request; hm_apr signs approvals and needs biometric/PIN per use. */
object Keys {
    const val REQ = "hm_req"
    const val APR = "hm_apr"
    private fun ks(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun reset() { val k = ks(); listOf(REQ, APR).forEach { if (k.containsAlias(it)) k.deleteEntry(it) } }

    fun ensure() {
        val k = ks()
        if (!k.containsAlias(REQ)) gen(REQ, false)
        if (!k.containsAlias(APR)) {
            try { gen(APR, true); Prefs.noLock = false } catch (e: Exception) { gen(APR, false); Prefs.noLock = true }
        }
    }

    private fun gen(alias: String, auth: Boolean) {
        fun spec(strong: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply {
                if (auth) {
                    setUserAuthenticationRequired(true)
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    setInvalidatedByBiometricEnrollment(false)
                }
                if (strong) setIsStrongBoxBacked(true)
            }.build()
        val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        try {
            g.initialize(spec(auth)); g.generateKeyPair()
        } catch (e: Exception) {
            if (!auth) throw e
            g.initialize(spec(false)); g.generateKeyPair()
        }
    }

    fun pub(alias: String): String = Base64.encodeToString(ks().getCertificate(alias).publicKey.encoded, Base64.NO_WRAP)

    fun sign(data: ByteArray): String = Signature.getInstance("SHA256withECDSA").run {
        initSign(ks().getKey(REQ, null) as PrivateKey); update(data); Base64.encodeToString(sign(), Base64.NO_WRAP)
    }

    fun approvalSignature(): Signature = Signature.getInstance("SHA256withECDSA").apply { initSign(ks().getKey(APR, null) as PrivateKey) }
}

class ApiError(val code: Int, msg: String) : Exception(msg)

/** A reset invalidates both in-flight responses and queued publications, even after switching back. */
internal class SessionEpoch {
    private var generation = 0L
    @Synchronized fun capture(): Long = generation
    @Synchronized fun invalidate() { generation++ }
    @Synchronized fun <T> commit(ticket: Long, block: () -> T): T {
        if (ticket != generation) throw CancellationException("Connection changed")
        return block()
    }
}

/** Keep cancellation attached until the body is consumed, not just until headers arrive. */
internal suspend fun <T> Call.awaitBody(read: (Response) -> T): T = suspendCancellableCoroutine { k ->
    k.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (k.isActive) k.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use { if (!k.isActive) return; read(it) }
                k.resume(result)
            } catch (e: Exception) { if (k.isActive) k.resumeWithException(e) }
        }
    })
}

/** Hash the serialized wire body, not metadata; only an Okio segment is buffered. */
internal fun requestBodySha256(body: RequestBody?, checkpoint: () -> Unit = {}): String {
    checkpoint()
    if (body != null && (body.isOneShot() || body.isDuplex()))
        throw IOException("Signing requires a repeatable request body")
    val hash = HashingSink.sha256(blackholeSink())
    val sink = object : ForwardingSink(hash) {
        override fun write(source: okio.Buffer, byteCount: Long) {
            checkpoint()
            super.write(source, byteCount)
        }
    }.buffer()
    sink.use { body?.writeTo(it) }
    checkpoint()
    return hash.hash.hex()
}

/** Copy once from potentially non-repeatable providers; caller owns the completed snapshot. */
internal fun spoolUpload(input: InputStream, directory: File, checkpoint: () -> Unit): File {
    var temp: File? = null
    try {
        input.use { source ->
            checkpoint()
            val file = File.createTempFile("hm-upload-", ".part", directory)
            temp = file
            file.outputStream().use { out ->
                val bytes = ByteArray(64 * 1024)
                while (true) {
                    checkpoint()
                    val count = source.read(bytes)
                    checkpoint()
                    if (count < 0) break
                    out.write(bytes, 0, count)
                }
            }
            checkpoint()
            return file
        }
    } catch (e: Throwable) {
        temp?.delete()
        throw e
    }
}

/** Only use with a private, completed snapshot kept alive until the call finishes. */
internal fun uploadFileBody(file: File, mime: String, checkpoint: () -> Unit): RequestBody = object : RequestBody() {
    private val length = file.length()
    override fun contentType() = mime.toMediaType()
    override fun contentLength() = length
    override fun writeTo(sink: BufferedSink) {
        checkpoint()
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            var sent = 0L
            while (true) {
                checkpoint()
                val count = input.read(bytes)
                checkpoint()
                if (count < 0) break
                sink.write(bytes, 0, count)
                sent += count
            }
            if (sent != length) throw IOException("Upload snapshot size changed")
        }
    }
}

object Api {
    internal val epoch = SessionEpoch()
    private data class Gate(val epoch: SessionEpoch, val generation: Long)
    private data class Identity(val generation: Long, val capability: Gate?, val base: String, val token: String?, val cfId: String?, val cfSecret: String?)
    private fun checkIdentity(identity: Identity) = epoch.commit(identity.generation) {
        identity.capability?.let { it.epoch.commit(it.generation) { } }
        Unit
    }
    private val JSON = "application/json".toMediaType()
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(180, TimeUnit.SECONDS)
            // Redirects can forward CF credentials, signatures and bodies to a different origin.
            // Surface 3xx through the existing API error path; derived SSE/download clients inherit this.
            .followRedirects(false).followSslRedirects(false)
            .addInterceptor(Interceptor {
                try { signer(it) } catch (e: IOException) { throw e }
                catch (e: Exception) { throw IOException("Request signing failed", e) }
            }).build()
    }
    val sse: OkHttpClient by lazy { http.newBuilder().readTimeout(75, TimeUnit.SECONDS).build() }
    val plain: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            // Pairing carries CF credentials and a one-time code even before bearer authentication.
            .followRedirects(false).followSslRedirects(false).build()
    }

    private fun signer(chain: Interceptor.Chain): Response {
        val r = chain.request()
        val identity = r.tag(Identity::class.java) ?: throw IOException("Missing connection identity")
        try { checkIdentity(identity) } catch (e: CancellationException) { throw IOException("Connection or capability changed", e) }
        val b = r.newBuilder()
        identity.cfId?.let { b.header("CF-Access-Client-Id", it); b.header("CF-Access-Client-Secret", identity.cfSecret ?: "") }
        val tok = identity.token
        if (tok != null) {
            val ts = r.header("X-HM-Ts") ?: (System.currentTimeMillis() / 1000).toString()
            val digest = requestBodySha256(r.body) {
                if (chain.call().isCanceled()) throw IOException("Canceled")
                checkIdentity(identity)
            }
            val q = r.url.encodedQuery
            val canon = "${r.method}\n${r.url.encodedPath}${if (q != null) "?$q" else ""}\n$ts\n$digest"
            b.header("Authorization", "Bearer $tok").header("X-HM-Ts", ts).header("X-HM-Sig", Keys.sign(canon.toByteArray()))
        }
        checkIdentity(identity)
        if (chain.call().isCanceled()) throw IOException("Canceled")
        return chain.proceed(b.build())
    }

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    fun url(p: String) = (Prefs.base ?: throw ApiError(0, "Not paired")).trimEnd('/') + p

    internal fun request(p: String): Request.Builder = synchronized(epoch) {
        if (!Hub.features.value.allows(p)) throw ApiError(0, "This feature isn't available on this machine")
        val gate = Hub.epochFor(p)?.let { Gate(it, it.capture()) }
        val identity = Identity(epoch.capture(), gate, Prefs.base ?: throw ApiError(0, "Not paired"),
            Prefs.token ?: throw ApiError(0, "Not paired"), Prefs.cfId, Prefs.cfSecret)
        Request.Builder().url(identity.base.trimEnd('/') + p).tag(Identity::class.java, identity)
    }

    private suspend fun <T> read(req: Request, client: OkHttpClient = http, body: (Response) -> T): T {
        currentCoroutineContext().ensureActive()
        val result = client.newCall(req).awaitBody(body)
        currentCoroutineContext().ensureActive()
        req.tag(Identity::class.java)?.let { checkIdentity(it) }
        if (!Hub.features.value.allows(req.url.encodedPath)) throw CancellationException("Feature disabled")
        return result
    }

    private suspend fun exec(req: Request, client: OkHttpClient = http): JSONObject = read(req, client) { resp ->
        val t = resp.body.string()
        if (!resp.isSuccessful) {
            val m = runCatching { JSONObject(t).str("error") }.getOrNull().orEmpty()
            throw ApiError(resp.code, m.ifBlank { if (resp.code == 401) "Not authorized (HTTP 401)" else "HTTP ${resp.code}" })
        }
        if (t.isBlank()) JSONObject() else JSONObject(t)
    }

    suspend fun get(p: String): JSONObject = exec(request(p).build())
    suspend fun post(p: String, body: JSONObject = JSONObject(), headers: Map<String, String> = emptyMap()): JSONObject =
        exec(request(p).post(body.toString().toRequestBody(JSON)).apply { headers.forEach { (k, v) -> header(k, v) } }.build())
    suspend fun put(p: String, body: JSONObject, headers: Map<String, String> = emptyMap()): JSONObject =
        exec(request(p).put(body.toString().toRequestBody(JSON)).apply { headers.forEach { (k, v) -> header(k, v) } }.build())
    suspend fun patch(p: String, body: JSONObject): JSONObject = exec(request(p).patch(body.toString().toRequestBody(JSON)).build())
    suspend fun delete(p: String): JSONObject = exec(request(p).delete().build())
    suspend fun upload(name: String, mime: String, bytes: ByteArray, generation: Long = epoch.capture()): JSONObject =
        exec(epoch.commit(generation) {
            request("/v1/upload?name=${Uri.encode(name)}&mime=${Uri.encode(mime)}").post(bytes.toRequestBody(mime.toMediaType())).build()
        })
    /** The media helpers own and retain this private snapshot through completion/cancellation. */
    internal suspend fun upload(name: String, mime: String, snapshot: File, generation: Long): JSONObject {
        val context = currentCoroutineContext()
        val body = uploadFileBody(snapshot, mime) {
            context.ensureActive()
            epoch.commit(generation) { }
        }
        return exec(epoch.commit(generation) {
            request("/v1/upload?name=${Uri.encode(name)}&mime=${Uri.encode(mime)}").post(body).build()
        })
    }

    /** Preserve positional and trailing progress-lambda callers without metadata. */
    suspend fun download(p: String, dest: java.io.File, progress: (Float) -> Unit) =
        download(p, dest, null, progress)

    /** Validate transport and nonnegative metadata lengths before publishing the completed temp file. */
    suspend fun download(p: String, dest: java.io.File, expectedSize: Long?, progress: (Float) -> Unit) {
        val req = request(p).build()
        withContext(Dispatchers.IO) {
            val big = http.newBuilder().readTimeout(5, TimeUnit.MINUTES).build()
            // Concurrent downloads must not share or delete one another's partial file.
            val tmp = java.io.File.createTempFile("hm-download-", ".part", dest.absoluteFile.parentFile)
            try {
                read(req, big) { r ->
                    if (!r.isSuccessful) throw ApiError(r.code, "HTTP ${r.code}")
                    val total = r.body.contentLength()
                    r.body.byteStream().use { inp ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024); var done = 0L; var last = -1
                            while (true) {
                                val n = inp.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                if (total > 0) { val pc = (done * 100 / total).toInt(); if (pc != last) { last = pc; progress(pc / 100f) } }
                            }
                        }
                    }
                    val actual = tmp.length()
                    if (total >= 0 && actual != total) throw ApiError(0, "Download cut off at $actual of $total bytes")
                    if (expectedSize != null && expectedSize >= 0 && actual != expectedSize)
                        throw ApiError(0, "Download size mismatch: received $actual of $expectedSize bytes")
                }
                currentCoroutineContext().ensureActive()
                epoch.commit(req.tag(Identity::class.java)!!.generation) {
                    if (!tmp.renameTo(dest)) throw ApiError(0, "Couldn't save download")
                }
            } finally { tmp.delete() }
        }
    }

    suspend fun bytes(p: String): ByteArray = read(request(p).build()) {
        if (!it.isSuccessful) throw ApiError(it.code, "HTTP ${it.code}")
        it.body.bytes()
    }

    suspend fun pair(link: Uri): Unit = withContext(Dispatchers.IO) {
        val u = link.getQueryParameter("u") ?: throw ApiError(0, "That link has no server address")
        val c = link.getQueryParameter("c") ?: throw ApiError(0, "That link has no pairing code")
        val ticket = epoch.capture()
        val cfId = link.getQueryParameter("i")
        val cfSecret = link.getQueryParameter("s")
        Keys.ensure()  // device keys are per phone, not per machine
        val body = JSONObject().put("code", c).put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("pub", Keys.pub(Keys.REQ)).put("apub", Keys.pub(Keys.APR)).put("lock", !Prefs.noLock)
        // Pair with isolated credentials; failure/cancellation leaves the active machine untouched.
        val req = Request.Builder().url(u.trimEnd('/') + "/v1/pair").post(body.toString().toRequestBody(JSON))
            .apply { if (cfId != null) { header("CF-Access-Client-Id", cfId); header("CF-Access-Client-Secret", cfSecret ?: "") } }.build()
        val o = exec(req, plain)
        val token = o.getString("token").takeIf { it.isNotBlank() } ?: throw ApiError(0, "Missing pairing token")
        val device = o.getString("device_id").takeIf { it.isNotBlank() } ?: throw ApiError(0, "Missing device ID")
        currentCoroutineContext().ensureActive()
        epoch.commit(ticket) {
            Hub.reset()
            Prefs.base = u; Prefs.cfId = cfId; Prefs.cfSecret = cfSecret
            o.optJSONObject("cf")?.let { cf -> cf.str("i").takeIf { it.isNotBlank() }?.let { Prefs.cfId = it; Prefs.cfSecret = cf.str("s") } }
            Prefs.deviceId = device
            Prefs.server = o.optJSONObject("server")?.str("name")
            Prefs.profile = o.optJSONObject("server")?.str("profile")
            Prefs.fcmCfg = null; Prefs.fcmToken = null
            Prefs.token = token
        }
    }
}
