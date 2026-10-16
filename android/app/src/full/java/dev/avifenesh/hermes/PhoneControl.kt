package dev.avifenesh.hermes

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import org.json.JSONObject

object PhoneNotice {
    private const val ID = 7106
    fun available(c: Context): Boolean {
        Notify.channels(c)
        val channel = c.getSystemService(NotificationManager::class.java).getNotificationChannel(Notify.CH_LIVE)
        return Notify.can(c) && androidx.core.app.NotificationManagerCompat.from(c).areNotificationsEnabled() &&
            channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun show(c: Context) {
        if (!available(c)) return
        Notify.channels(c)
        val stop = PendingIntent.getBroadcast(c, ID, Intent(c, PhoneStopReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(c, ID, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, Notify.CH_LIVE).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Phone control is on").setContentText("Your agent can read and operate this unlocked phone.")
            .setContentIntent(open).setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "Stop control", stop).build()
        if (Notify.can(c)) c.getSystemService(NotificationManager::class.java).notify(ID, n)
    }
    fun hide(c: Context) { c.getSystemService(NotificationManager::class.java).cancel(ID) }
}
class PhoneStopReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent?) { PhoneService.stop(c) }
}

@Composable
fun PhoneControlPanel() {
    val c = LocalContext.current
    val act = LocalAct.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var enabled by remember { mutableStateOf(PhoneService.enabled(c)) }
    val armed by PhoneService.controlState.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    fun refresh() {
        enabled = PhoneService.enabled(c)
        scope.launch {
            runCatching { Api.get("/v1/phone") }.onSuccess {
                ready = true; error = null
                // A server-side arm alone cannot bypass the device-local kill switch.
                if (!it.optBoolean("this") || !enabled) { Prefs.phoneArmed = false; PhoneNotice.hide(c) }
                PhoneService.controlState.value = Prefs.phoneArmed
            }.onFailure { ready = false; error = it.message }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Panel {
        SettingRow("Accessibility service", if (enabled) "Enabled in Android settings." else "Enable Hermes phone control in Android Accessibility settings.") {
            Btn("Open", primary = false) { PhoneService.openSettings(c) }
        }
        Line()
        SettingRow("Allow agent control", if (armed) "On. The agent can read, tap, type, swipe and open apps." else "Off. No screen content or commands are sent.") {
            Toggle(armed, { on ->
                if (!on) { PhoneService.stop(c); return@Toggle }
                if (busy) return@Toggle
                if (!enabled) { error = "Enable the Accessibility service first"; return@Toggle }
                if (!ready) { error = "Phone control is not available on the server yet"; return@Toggle }
                if (!PhoneNotice.available(c)) {
                    error = "Enable the Live runs & connection notification channel so Stop control stays accessible"
                    c.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, Notify.CH_LIVE))
                    return@Toggle
                }
                val generation = PhoneService.activationGeneration()
                val base = Prefs.base
                val device = Prefs.deviceId
                scope.launch {
                    busy = true; error = null
                    var session: String? = null
                    runCatching {
                        val arming = Guard.call(act, "phone", "arm", true, "Allow phone control") { headers -> Api.post("/v1/phone/arm", JSONObject().put("on", true), headers) }
                        session = arming.str("session").ifBlank { null }
                        check(generation == PhoneService.activationGeneration() && base == Prefs.base && device == Prefs.deviceId &&
                            PhoneService.enabled(c) && PhoneService.inst != null && PhoneNotice.available(c)) { "Activation cancelled because control, pairing or Accessibility changed" }
                        val status = Api.get("/v1/phone")
                        check(status.optBoolean("this") && status.str("session") == session) { "Server did not arm this phone or activation was superseded" }
                        check(generation == PhoneService.activationGeneration() && base == Prefs.base && device == Prefs.deviceId &&
                            PhoneService.enabled(c) && PhoneService.inst != null && PhoneNotice.available(c)) { "Activation cancelled because control, pairing or Accessibility changed" }
                        Prefs.phoneArmed = true; PhoneNotice.show(c); LiveService.sync(c); Hub.connect(); PhoneService.pull()
                    }.onFailure { error = it.message }
                    if (session != null && !Prefs.phoneArmed && base == Prefs.base && device == Prefs.deviceId) {
                        runCatching { Api.post("/v1/phone/arm", JSONObject().put("on", false).put("session", session)) }
                    }
                    busy = false
                }
            })
        }
        if (armed) {
            Line()
            Column(androidx.compose.ui.Modifier.fillMaxWidth().padding(16.dp)) {
                Btn("Stop control", androidx.compose.ui.Modifier.fillMaxWidth(), danger = true) { PhoneService.stop(c) }
            }
        }
    }
    Spacer(androidx.compose.ui.Modifier.height(14.dp))
    Text("First setup for a sideloaded APK: Android may block Accessibility. Open Android Settings → Apps → Hermes → ⋮ → Allow restricted settings, then enable the service here. You only need to do this once.", style = sans(13f, 400, P.mu, 19f), modifier = androidx.compose.ui.Modifier.padding(horizontal = 20.dp))
    Spacer(androidx.compose.ui.Modifier.height(12.dp))
    Text("Control works only while the screen is on and unlocked. Android blocks screenshots of protected screens. Password fields stay private. Turn control off here or from the notification at any time.", style = sans(13f, 400, P.mu, 19f), modifier = androidx.compose.ui.Modifier.padding(horizontal = 20.dp))
    if (busy) Text("Confirm with fingerprint or PIN…", style = sans(13f, 500, P.acct), modifier = androidx.compose.ui.Modifier.padding(20.dp))
    error?.let { Text(it, style = sans(13f, 500, P.tx2), modifier = androidx.compose.ui.Modifier.padding(20.dp)) }
}
