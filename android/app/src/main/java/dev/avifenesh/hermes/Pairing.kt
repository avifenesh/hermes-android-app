package dev.avifenesh.hermes

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest

/**
 * One redemption per pairing code, process-wide. The pairing screen can be recreated (rotation, theme change) while
 * a redemption runs, and its new composition delivers the same link again; that must not send the code twice.
 */
internal class PairGate(redeemed: Collection<String> = emptyList()) {
    enum class Verdict { Start, InFlight, Redeemed, Attempted }

    private var inFlight: String? = null
    private val attempted = HashSet<String>()
    private val done = LinkedHashSet(redeemed)

    /** [auto]: the screen's own effect for a delivered link, not a tap. A failed automatic attempt is not repeated by
     *  recomposition; a tap retries. One redemption at a time, like the screen's old busy flag. */
    @Synchronized fun begin(key: String, auto: Boolean): Verdict = when {
        key in done -> Verdict.Redeemed
        inFlight != null -> Verdict.InFlight
        auto && key in attempted -> Verdict.Attempted
        else -> { inFlight = key; attempted += key; Verdict.Start }
    }

    @Synchronized fun finish(key: String, redeemed: Boolean) {
        if (inFlight != key) return
        inFlight = null
        if (redeemed) done += key
    }

    @Synchronized fun isRedeemed(key: String): Boolean = key in done

    /** The link's earlier automatic attempt no longer blocks one: its screen left, or it was delivered again. */
    @Synchronized fun forget(key: String) { attempted -= key }

    @Synchronized fun redeemed(): List<String> = done.toList()

    companion object {
        /** What is remembered about a redeemed link: sha256 of server and code, never the code. */
        fun key(server: String, code: String): String = MessageDigest.getInstance("SHA-256")
            .digest((server.trim().trimEnd('/') + "\n" + code).toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/**
 * One redemption. Cancel and the credential commit are decided once, under one lock: either Cancel came first and
 * the response is dropped (no machine switch), or the pairing had already committed and Cancel is too late.
 */
internal class PairAttempt(val key: String) {
    private var cancelled = false
    private var committed = false

    /** Inside Api.pair's commit, right before credentials are written. False: cancelled, write nothing. */
    @Synchronized fun claimCommit(): Boolean = if (cancelled) false else { committed = true; true }

    /** False when the pairing already committed. */
    @Synchronized fun cancel(): Boolean = if (committed) false else { cancelled = true; true }

    val isCancelled: Boolean @Synchronized get() = cancelled
}

/**
 * What the pairing screen does with a delivered link, kept outside composition. The same link delivered again
 * (onNewIntent while the screen shows it: an equal Uri, so nothing recomposes) is a fresh offer: the prompt comes
 * back and the earlier failure goes. Generic so JVM tests can use plain strings for android.net.Uri.
 */
internal class PairOffers<L : Any> {
    enum class Next { Nothing, AlreadyPaired, Confirm, Redeem }

    /** "Pair with HOST?" while paired: only a deliberate tap may switch this phone to another machine. */
    val pending = mutableStateOf<L?>(null)
    /** The last redemption's failure, kept across screen recreation until the next delivery or [clear]. */
    val failure = mutableStateOf<String?>(null)

    fun delivered(link: L, hasCode: Boolean, paired: Boolean, redeemed: Boolean): Next {
        failure.value = null
        pending.value = null
        return when {
            !hasCode -> Next.Nothing
            redeemed -> Next.AlreadyPaired
            paired -> { pending.value = link; Next.Confirm }
            else -> Next.Redeem
        }
    }

    /** The tap on "Pair with this machine". */
    fun take(): L? = pending.value.also { pending.value = null }

    fun failed(message: String) { failure.value = message }

    fun clear() { pending.value = null; failure.value = null }
}

/** Signs one network attempt of the pairing request with the canonical request message. */
internal class PairSigner(private val body: ByteArray, private val sign: (ByteArray) -> String,
                          private val now: () -> Long) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val r = chain.request()
        val ts = now().toString()
        val q = r.url.encodedQuery
        val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val canon = "${r.method}\n${r.url.encodedPath}${if (q != null) "?$q" else ""}\n$ts\n$hash"
        // Keystore failures (ProviderException, SignatureException, a missing key) are not IOExceptions. OkHttp
        // rethrows those on its dispatcher thread, which kills the app; as an IOException the call fails normally.
        val sig = try { sign(canon.toByteArray()) } catch (e: IOException) { throw e } catch (e: Exception) {
            throw IOException("Pairing signature failed", e)
        }
        return chain.proceed(r.newBuilder().header("X-HM-Ts", ts).header("X-HM-Sig", sig).build())
    }
}

/**
 * The client for one pairing POST. OkHttp silently re-sends a request when a pooled connection was dead; the server
 * refuses a re-sent signature as a replay. A network interceptor runs per attempt, so each one is signed afresh.
 */
internal fun pairingClient(base: OkHttpClient, body: ByteArray, sign: (ByteArray) -> String,
                           now: () -> Long = { System.currentTimeMillis() / 1000 }): OkHttpClient =
    base.newBuilder().addNetworkInterceptor(PairSigner(body, sign, now)).build()

/** The pairing in progress. It runs on [Hub.scope], so it outlives the pairing screen's composition. */
object Pairing {
    val busy = mutableStateOf(false)
    /** The prompt and failure the pairing screen shows; set by deliveries, so a re-delivery re-arms them. */
    internal val offers = PairOffers<Uri>()
    private val gate by lazy { PairGate(Prefs.pairRedeemed) }
    // Main thread only: redeem, cancel and the attempt coroutine (Dispatchers.Main) never interleave.
    private var attempt: PairAttempt? = null
    private var job: Job? = null
    private var failedKey: String? = null

    private fun key(link: Uri): String? {
        val u = link.getQueryParameter("u") ?: return null
        val c = link.getQueryParameter("c") ?: return null
        return PairGate.key(u, c)
    }

    /** A link this phone already redeemed: say so instead of offering a pairing that can only fail. */
    fun alreadyPaired(link: Uri) {
        val base = link.getQueryParameter("u")?.trim()?.trimEnd('/')
        val known = Prefs.paired && runCatching { Machines.all().any { it.str("base").trimEnd('/') == base } }.getOrDefault(false)
        if (known) {
            offers.clear()
            Ui.pairLink.value = null
            Ui.say("Already paired with " + (base?.let { Uri.parse(it).host } ?: "this machine"))
        } else {
            offers.failed("That pairing link was already used. Run hermes mobile pair again.")
        }
    }

    /**
     * A link from an intent (onCreate or onNewIntent, also the same link again): a genuine re-open. The earlier failed
     * attempt of it no longer blocks one, its error goes, and it is offered again: a prompt while paired (a link
     * alone never switches machines), a redemption otherwise. Prefs, not Ui.paired: on a cold start this runs before
     * the activity has set Ui.paired.
     */
    fun delivered(ctx: Context, link: Uri) {
        val k = key(link)
        k?.let { gate.forget(it) }
        failedKey = null
        val next = offers.delivered(link, hasCode = link.getQueryParameter("c") != null, paired = Prefs.paired,
            redeemed = k != null && gate.isRedeemed(k))
        when (next) {
            PairOffers.Next.AlreadyPaired -> alreadyPaired(link)
            PairOffers.Next.Redeem ->
                if (link.scheme == "hermes" && link.host == "pair") redeem(ctx, link, auto = true)
                else offers.failed("That isn't a Hermes pairing link")
            PairOffers.Next.Confirm, PairOffers.Next.Nothing -> {}
        }
    }

    /** The pairing screen really left (not a configuration change): drop the failure, let a re-open retry. */
    fun clear() {
        offers.clear()
        failedKey?.let { gate.forget(it) }
        failedKey = null
    }

    /** Cancel on the pairing screen. The response of a cancelled attempt never switches the active machine. */
    fun cancel() {
        val a = attempt
        if (a != null) {
            if (!a.cancel()) return  // already committed: the pairing completes and the screen leaves on its own
            attempt = null
            job?.cancel()
            gate.finish(a.key, redeemed = false)
            gate.forget(a.key)
            busy.value = false
        }
        clear()
    }

    fun redeem(ctx: Context, link: Uri, auto: Boolean) {
        val k = key(link)
        if (k == null) {
            offers.failed(if (link.getQueryParameter("u") == null) "That link has no server address" else "That link has no pairing code")
            return
        }
        when (gate.begin(k, auto)) {
            PairGate.Verdict.Redeemed -> { alreadyPaired(link); return }
            PairGate.Verdict.InFlight, PairGate.Verdict.Attempted -> return
            PairGate.Verdict.Start -> {}
        }
        val a = PairAttempt(k)
        attempt = a
        busy.value = true
        offers.failure.value = null
        failedKey = null
        val app = ctx.applicationContext
        job = Hub.scope.launch(Dispatchers.Main) {
            var ok = false
            try {
                PhoneService.disarm(app)
                Machines.park()
                // pair commits credentials atomically only after a valid response, and only if not cancelled by then.
                // Nothing to roll back on failure or cancel.
                Api.pair(link) { a.claimCommit() }
                ok = true
                Prefs.seq = 0; Prefs.nseq = 0; LiveService.firstPair()
                Machines.park()
                Ui.stack.clear(); Ui.tab.value = "inbox"
                Ui.pairLink.value = null; Ui.paired.value = true
                // Main owns sync / metadata / push bootstrap after the pairing screen leaves composition.
                Ui.say("Paired with " + (Prefs.server ?: "Hermes"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!a.isCancelled) {
                    failedKey = k
                    offers.failed(when {
                        e is ApiError && e.code == 403 -> "That code is used or expired. Run hermes mobile pair again."
                        // The server saw this exact request before (a transport re-send): a new attempt can still pair.
                        e is ApiError && e.code == 409 -> "This phone already sent that pairing request. Open the link again to retry."
                        else -> e.message ?: e.toString()
                    })
                }
            } finally {
                if (attempt === a) {  // a cancelled attempt was already settled by cancel()
                    attempt = null
                    gate.finish(k, ok)
                    busy.value = false
                }
                if (ok) Prefs.pairRedeemed = gate.redeemed()
            }
        }
    }
}
