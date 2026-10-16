package dev.avifenesh.hermes

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

/** Fallback when push isn't configured: keeps the event stream open; doubles as the live-run notification. */
class LiveService : Service() {
    private var job: Job? = null
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(this, 7, Notify.live(this, Hub.working.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: RuntimeException) {
            // Permission, background-start restrictions, or the Android 15 dataSync budget can reject promotion.
            Log.w("hermes", "live connection unavailable: ${e.message}")
            stopSelf(); return START_NOT_STICKY
        }
        if (!Prefs.paired || !(Prefs.live || Prefs.phoneArmed)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        Hub.connect()
        if (job == null) job = Hub.scope.launch {
            combine(Hub.working, Hub.online, Hub.chats) { w, on, ch -> Triple(w.toString(), on, w.keys.map { ch[it]?.title }) }
                .distinctUntilChanged()
                .collect { Notify.post(this@LiveService, 7, Notify.live(this@LiveService, Hub.working.value)) }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 limits dataSync foreground work to six hours in the background.
        // WorkManager remains scheduled; do not restart a service whose budget is exhausted.
        job?.cancel(); job = null
        if (!Hub.foreground) Hub.disconnect()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel(); job = null
        if (!Hub.foreground) Hub.disconnect()
        super.onDestroy()
    }

    companion object {
        fun sync(c: Context) {
            val i = Intent(c, LiveService::class.java)
            if (Prefs.paired && (Prefs.live || Prefs.phoneArmed)) runCatching { ContextCompat.startForegroundService(c, i) } else c.stopService(i)
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
                        if (locked(c)) { withContext(Dispatchers.Main) { Toast.makeText(c, "Unlock the phone to send", Toast.LENGTH_LONG).show() }; return@launch }
                        val cid = i.getStringExtra("chat") ?: return@launch
                        val text = RemoteInput.getResultsFromIntent(i)?.getCharSequence("reply")?.toString().orEmpty().trim()
                        if (text.isNotEmpty()) Hub.send(cid, text)
                        Notify.cancelChat(c, cid)
                    }
                    "read" -> { val cid = i.getStringExtra("chat") ?: return@launch; Hub.markRead(cid) }
                    "fleet_reply" -> {
                        val id = i.getStringExtra("agent") ?: return@launch
                        if (locked(c)) { withContext(Dispatchers.Main) { Toast.makeText(c, "Unlock the phone to send", Toast.LENGTH_LONG).show() }; return@launch }
                        val text = (i.getStringExtra("text") ?: RemoteInput.getResultsFromIntent(i)?.getCharSequence("reply")?.toString()).orEmpty().trim()
                        if (text.isNotEmpty()) {
                            val r = runCatching { Fleet.send(id, text) }
                            val msg = r.fold({ it.str("line").ifBlank { "Sent to " + i.getStringExtra("name") } },
                                { if ((it as? ApiError)?.code == 403) "Needs your fingerprint: open the session in Hermes" else "Not sent: ${it.message}" })
                            withContext(Dispatchers.Main) { Toast.makeText(c, msg, Toast.LENGTH_LONG).show() }
                        }
                        Fleet.cancel(c, id)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("hermes", "action ${i.action} failed: ${e.message}")
                withContext(Dispatchers.Main) { Toast.makeText(c, "Hermes: ${e.message}", Toast.LENGTH_LONG).show() }
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
                        r.isFailure -> done(r.exceptionOrNull()?.message ?: "Failed")
                        a?.state == "expired" -> done("That approval already expired")
                        else -> { Notify.cancelApproval(act, aid); done(null) }
                    }
                }
            }
        }
        if (choice == "deny") { submit(null, null); return }
        val ts = (System.currentTimeMillis() / 1000).toString()
        val payload = "approve\n$aid\n$choice\n$ts".toByteArray()
        val sig = try { Keys.approvalSignature() } catch (e: Exception) { done("Approval key unavailable: ${e.message}"); return }
        if (Prefs.noLock) {
            val s = runCatching { sig.update(payload); Base64.encodeToString(sig.sign(), Base64.NO_WRAP) }.getOrNull()
            if (s == null) done("Signing failed") else submit(ts, s)
            return
        }
        val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val s = result.cryptoObject?.signature ?: return done("No signature")
                val out = try { s.update(payload); Base64.encodeToString(s.sign(), Base64.NO_WRAP) } catch (e: Exception) { return done(e.message ?: "Signing failed") }
                submit(ts, out)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                done(if (cancelled) "" else errString.toString())
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (choice == "once") "Approve command" else "Approve for $choice")
            .setSubtitle(cmd.take(140))
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(sig))
    }
}

class ApproveActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        val aid = intent.getStringExtra("aid") ?: return finish()
        val choice = intent.getStringExtra("choice") ?: "once"
        Approvals.run(this, aid, choice, intent.getStringExtra("cmd").orEmpty()) { err ->
            when {
                err == null -> Toast.makeText(this, "Approved", Toast.LENGTH_SHORT).show()
                err.isNotEmpty() -> Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }
}


/** Text typed from a notification on the lock screen reaches the agent with nobody verified: refuse it there. */
private fun locked(c: Context) = c.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
