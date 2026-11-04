package dev.avifenesh.hermes

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    @Synchronized fun redeemed(): List<String> = done.toList()

    companion object {
        /** What is remembered about a redeemed link: sha256 of server and code, never the code. */
        fun key(server: String, code: String): String = MessageDigest.getInstance("SHA-256")
            .digest((server.trim().trimEnd('/') + "\n" + code).toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** The pairing in progress. It runs on [Hub.scope], so it outlives the pairing screen's composition. */
object Pairing {
    val busy = mutableStateOf(false)
    /** The last redemption's failure, kept across screen recreation; the screen clears it when it really leaves. */
    val error = mutableStateOf<String?>(null)
    private val gate by lazy { PairGate(Prefs.pairRedeemed) }

    private fun key(link: Uri): String? {
        val u = link.getQueryParameter("u") ?: return null
        val c = link.getQueryParameter("c") ?: return null
        return PairGate.key(u, c)
    }

    fun redeemed(link: Uri): Boolean = key(link)?.let { gate.isRedeemed(it) } == true

    /** A link this phone already redeemed: say so instead of offering a pairing that can only fail. */
    fun alreadyPaired(link: Uri) {
        val base = link.getQueryParameter("u")?.trim()?.trimEnd('/')
        val known = Ui.paired.value && runCatching { Machines.all().any { it.str("base").trimEnd('/') == base } }.getOrDefault(false)
        if (known) {
            error.value = null
            Ui.pairLink.value = null
            Ui.say("Already paired with " + (base?.let { Uri.parse(it).host } ?: "this machine"))
        } else {
            error.value = "That pairing link was already used. Run hermes mobile pair again."
        }
    }

    fun redeem(ctx: Context, link: Uri, auto: Boolean) {
        val k = key(link)
        if (k == null) {
            error.value = if (link.getQueryParameter("u") == null) "That link has no server address" else "That link has no pairing code"
            return
        }
        when (gate.begin(k, auto)) {
            PairGate.Verdict.Redeemed -> { alreadyPaired(link); return }
            PairGate.Verdict.InFlight, PairGate.Verdict.Attempted -> return
            PairGate.Verdict.Start -> {}
        }
        busy.value = true
        error.value = null
        val app = ctx.applicationContext
        Hub.scope.launch(Dispatchers.Main) {
            var ok = false
            try {
                PhoneService.disarm(app)
                Machines.park()
                // pair commits credentials atomically only after a valid response. Nothing to roll back on failure.
                Api.pair(link)
                ok = true
                Prefs.seq = 0; Prefs.nseq = 0; Prefs.live = true
                Machines.park()
                Ui.stack.clear(); Ui.tab.value = "inbox"
                Ui.pairLink.value = null; Ui.paired.value = true
                // Main owns sync / metadata / push bootstrap after the pairing screen leaves composition.
                Ui.say("Paired with " + (Prefs.server ?: "Hermes"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = when (e) {
                    is ApiError -> if (e.code == 403) "That code is used or expired. Run hermes mobile pair again." else e.message
                    else -> e.message ?: e.toString()
                }
            } finally {
                gate.finish(k, ok)
                if (ok) Prefs.pairRedeemed = gate.redeemed()
                busy.value = false
            }
        }
    }
}
