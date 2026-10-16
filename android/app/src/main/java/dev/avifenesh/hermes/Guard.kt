package dev.avifenesh.hermes

import android.util.Base64
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Writes that loosen what the agent may do (approval mode, terminal, hooks, plugins...) need the same biometric key as
 * approvals, signed over "<kind>\n<key>\n<sha256(norm(value))>\n<ts>". norm() matches the server: true/false, null,
 * numbers as org.json writes them, strings as-is.
 */
object Guard {
    fun norm(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is Boolean -> if (v) "true" else "false"
        is Number -> JSONObject.numberToString(v)
        else -> v.toString()
    }

    suspend fun sign(act: FragmentActivity, kind: String, key: String, value: Any?, title: String): Map<String, String>? =
        suspendCancellableCoroutine { k ->
            val ts = (System.currentTimeMillis() / 1000).toString()
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(norm(value).toByteArray()).joinToString("") { "%02x".format(it) }
            val payload = "$kind\n$key\n$digest\n$ts".toByteArray()
            val sig = runCatching { Keys.approvalSignature() }.getOrNull() ?: run { k.resume(null); return@suspendCancellableCoroutine }
            fun done(s: java.security.Signature?) {
                val out = s?.let { runCatching { it.update(payload); Base64.encodeToString(it.sign(), Base64.NO_WRAP) }.getOrNull() }
                if (k.isActive) k.resume(out?.let { mapOf("X-HM-Ts" to ts, "X-HM-ASig" to it) })
            }
            if (Prefs.noLock) { done(sig); return@suspendCancellableCoroutine }
            BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) = done(r.cryptoObject?.signature)
                override fun onAuthenticationError(code: Int, s: CharSequence) { if (k.isActive) k.resume(null) }
            }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle("$key → ${norm(value).take(60)}")
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL).build(), BiometricPrompt.CryptoObject(sig))
        }

    /** Try plain; on 403 need_sig, ask for the fingerprint and retry once with the signature. */
    suspend fun call(act: FragmentActivity, kind: String, key: String, value: Any?, title: String, send: suspend (Map<String, String>) -> JSONObject): JSONObject {
        try { return send(emptyMap()) } catch (e: ApiError) { if (e.code != 403) throw e }
        val h = sign(act, kind, key, value, title) ?: throw ApiError(0, "Cancelled")
        return send(h)
    }
}
