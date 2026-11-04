package dev.avifenesh.hermes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class FcmService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Prefs.fcmToken = token
        if (Prefs.paired) Hub.scope.launch { runCatching { Api.post("/v1/fcm", JSONObject().put("token", token)) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!Prefs.paired) return
        if (message.data["t"] == "phone") { PhoneService.pull(); return }
        if (message.data["t"] == "wake") { Wake.onPush(this, message.data["a"].orEmpty()); return }
        WorkManager.getInstance(this).enqueueUniqueWork("wake", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
    }
}

class SyncWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        if (!Prefs.paired) return Result.success()
        val ticket = Api.epoch.capture()
        return try {
            Hub.sync()
            Api.epoch.commit(ticket) { }
            try { Hub.refreshMe() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            Api.epoch.commit(ticket) { }
            try { Wake.refresh(applicationContext) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            currentCoroutineContext().ensureActive()
            Api.epoch.commit(ticket) { }
            if (!Hub.foreground) Hub.notifyPending(applicationContext)
            if (Hub.features.value.fleet) {
                try { if (!Hub.foreground) Fleet.notifyNew(applicationContext) else Fleet.load() }
                catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
            Widget.update(applicationContext)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("hermes", "sync failed: ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pr = goAsync()
        val ticket = Api.epoch.capture()
        Hub.scope.launch {
            try {
                Api.epoch.commit(ticket) { }
                if (!Prefs.paired || i.getStringExtra("base")?.let { it != Prefs.base } == true) return@launch
                when (i.action) {
                    "deny" -> { val aid = i.getStringExtra("aid") ?: return@launch; Hub.resolve(aid, "deny", null, null); Notify.cancelApproval(c, aid) }
                    "reply" -> {
                        if (locked(c)) { withContext(Dispatchers.Main) { Toast.makeText(c, str(R.string.notify_unlock_to_send), Toast.LENGTH_LONG).show() }; return@launch }
                        val cid = i.getStringExtra("chat") ?: return@launch
                        val text = RemoteInput.getResultsFromIntent(i)?.getCharSequence("reply")?.toString().orEmpty().trim()
                        if (text.isNotEmpty()) Hub.send(cid, text)
                        Notify.cancelChat(c, cid)
                    }
                    "read" -> { val cid = i.getStringExtra("chat") ?: return@launch; Hub.markRead(cid) }
                    "fleet_reply" -> {
                        val id = i.getStringExtra("agent") ?: return@launch
                        if (locked(c)) { withContext(Dispatchers.Main) { Toast.makeText(c, str(R.string.notify_unlock_to_send), Toast.LENGTH_LONG).show() }; return@launch }
                        val text = (i.getStringExtra("text") ?: RemoteInput.getResultsFromIntent(i)?.getCharSequence("reply")?.toString()).orEmpty().trim()
                        if (text.isNotEmpty()) {
                            val r = runCatching { Fleet.send(id, text) }
                            val msg = r.fold({ it.str("line").ifBlank { str(R.string.notify_sent_to, bidiIsolate(i.getStringExtra("name").orEmpty())) } },
                                { if ((it as? ApiError)?.code == 403) str(R.string.notify_needs_fingerprint, bidiIsolate(APP_NAME)) else str(R.string.agent_not_sent, it.message?.let(::bidiIsolate)) })
                            withContext(Dispatchers.Main) { Toast.makeText(c, msg, Toast.LENGTH_LONG).show() }
                        }
                        Fleet.cancel(c, id)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("hermes", "action ${i.action} failed: ${e.message}")
                withContext(Dispatchers.Main) { Toast.makeText(c, "$APP_NAME: ${e.message}", Toast.LENGTH_LONG).show() }
            } finally { pr.finish() }
        }
    }
}

object Approvals {
    /** done(null) = success, done("") = cancelled, done(msg) = error */
    fun run(act: FragmentActivity, aid: String, choice: String, cmd: String, done: (String?) -> Unit) {
        val ticket = Api.epoch.capture()
        fun submit(ts: String?, sig: String?) {
            Hub.scope.launch {
                val r = runCatching { Api.epoch.commit(ticket) { }; Hub.resolve(aid, choice, ts, sig) }
                withContext(Dispatchers.Main) {
                    val a = r.getOrNull()
                    when {
                        r.isFailure -> done(r.exceptionOrNull()?.message ?: str(R.string.common_failed))
                        a?.state == "expired" -> done(str(R.string.approvals_expired))
                        else -> { Notify.cancelApproval(act, aid); done(null) }
                    }
                }
            }
        }
        if (choice == "deny") { submit(null, null); return }
        val ts = (System.currentTimeMillis() / 1000).toString()
        val payload = "approve\n$aid\n$choice\n$ts".toByteArray()
        val sig = try { Keys.approvalSignature() } catch (e: Exception) { done(str(R.string.approvals_key_unavailable, e.message?.let(::bidiIsolate))); return }
        if (Prefs.noLock) {
            val s = runCatching { sig.update(payload); Base64.encodeToString(sig.sign(), Base64.NO_WRAP) }.getOrNull()
            if (s == null) done(str(R.string.approvals_signing_failed)) else submit(ts, s)
            return
        }
        val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val s = result.cryptoObject?.signature ?: return done(str(R.string.approvals_no_signature))
                val out = try { s.update(payload); Base64.encodeToString(s.sign(), Base64.NO_WRAP) } catch (e: Exception) { return done(e.message ?: str(R.string.approvals_signing_failed)) }
                submit(ts, out)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                done(if (cancelled) "" else errString.toString())
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(promptTitle(choice))
            .setSubtitle(cmd.take(140))
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(sig))
    }

    /** A native session approval; same biometric proof, with session and generation binding. */
    fun runSession(act: FragmentActivity, sid: String, prompt: JSONObject, choice: String,
                   done: (String?) -> Unit) {
        val pid = prompt.optString("prompt_id")
        val generation = prompt.optLong("generation")
        val body = JSONObject().put("kind", "approval").put("choice", choice).put("generation", generation)
        fun submit(ts: String?, sig: String?) {
            Hub.scope.launch {
                val r = runCatching {
                    val headers = mutableMapOf<String, String>()
                    if (ts != null && sig != null) { headers["X-HM-Ts"] = ts; headers["X-HM-ASig"] = sig }
                    Api.put("/v1/sessions/${Uri.encode(sid)}/prompts/${Uri.encode(pid)}", body, headers)
                }
                withContext(Dispatchers.Main) {
                    if (r.isFailure) done(r.exceptionOrNull()?.message ?: str(R.string.common_failed))
                    else done(null)
                }
            }
        }
        if (choice == "deny") { submit(null, null); return }
        prove(act, promptTitle(choice),
            prompt.optString("command").ifBlank { prompt.optString("detail") },
            "approve\n$sid:$pid\n$choice", { done(it) }, ::submit)
    }

    /** A group chat member's command: the same biometric proof, bound to room, member and request. */
    fun runRoom(act: FragmentActivity, rid: String, a: RoomAction, choice: String, done: (String?) -> Unit) {
        fun submit(ts: String?, sig: String?) {
            Hub.scope.launch {
                val r = runCatching {
                    val headers = mutableMapOf<String, String>()
                    if (ts != null && sig != null) { headers["X-HM-Ts"] = ts; headers["X-HM-ASig"] = sig }
                    Rooms.act(rid, a, choice, headers)
                }
                withContext(Dispatchers.Main) { done(if (r.isFailure) r.exceptionOrNull()?.message ?: str(R.string.common_failed) else null) }
            }
        }
        if (choice == "deny") { submit(null, null); return }
        // Its own domain and server-side replay scope, one field per line (ids never contain a newline).
        prove(act, str(R.string.approvals_prompt_once), a.command.ifBlank { a.description },
            "room-approve\n$rid\n${a.member}\n${a.requestId}\n$choice", { done(it) }, ::submit)
    }

    /** Fingerprint prompt title for an approval scope; scopes the app does not know show as the server sent them. */
    private fun promptTitle(choice: String): String = when (choice) {
        "once" -> str(R.string.approvals_prompt_once)
        "session" -> str(R.string.approvals_prompt_session)
        "always" -> str(R.string.approvals_prompt_always)
        else -> str(R.string.approvals_prompt_scope, bidiIsolate(choice))
    }

    private fun prove(act: FragmentActivity, title: String, subtitle: String, prefix: String,
                      onError: (String) -> Unit, done: (String?, String?) -> Unit) {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val payload = "$prefix\n$ts".toByteArray()
        val sig = try { Keys.approvalSignature() } catch (e: Exception) { onError(str(R.string.approvals_key_unavailable, e.message?.let(::bidiIsolate))); return }
        if (Prefs.noLock) {
            val s = runCatching { sig.update(payload); Base64.encodeToString(sig.sign(), Base64.NO_WRAP) }.getOrNull()
            if (s == null) onError(str(R.string.approvals_signing_failed)) else done(ts, s)
            return
        }
        val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val s = result.cryptoObject?.signature ?: return onError(str(R.string.approvals_no_signature))
                val out = try { s.update(payload); Base64.encodeToString(s.sign(), Base64.NO_WRAP) } catch (e: Exception) { return onError(e.message ?: str(R.string.approvals_signing_failed)) }
                done(ts, out)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                onError(if (cancelled) "" else errString.toString())
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle.take(140))
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL).build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(sig))
    }
}

class ApproveActivity : FragmentActivity() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(Locales.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        val aid = intent.getStringExtra("aid") ?: return finish()
        val choice = intent.getStringExtra("choice") ?: "once"
        Approvals.run(this, aid, choice, intent.getStringExtra("cmd").orEmpty()) { err ->
            when {
                err == null -> Toast.makeText(this, str(R.string.approvals_approved), Toast.LENGTH_SHORT).show()
                err.isNotEmpty() -> Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }
}


/** Text typed from a notification on the lock screen reaches the agent with nobody verified: refuse it there. */
private fun locked(c: Context) = c.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
