@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.avifenesh.hermes

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Full-screen alarm surface over the lock screen. Nothing here stops the alarm except a proof the server accepts. */
class WakeActivity : FragmentActivity() {
    var mode = "home"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        Ui.theme.intValue = Prefs.theme
        setContent { HermesTheme(Ui.theme.intValue) { WakeScreen(this) } }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) true
        else super.onKeyDown(keyCode, event)

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (mode in setOf("read", "questions", "logic", "offline", "override")) Wake.touch()
        return super.dispatchTouchEvent(ev)
    }

    fun unlockThen(go: () -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) { go(); return }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { go() }
        })
    }
}

private fun hhmm(ts: Double): String = if (ts <= 0) "–" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date((ts * 1000).toLong()))

private fun shrink(f: File): String {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 1280) s *= 2
    val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: error("Could not read the photo")
    val k = 1280f / maxOf(b.width, b.height)
    val sc = if (k < 1f) Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true) else b
    val out = ByteArrayOutputStream()
    sc.compress(Bitmap.CompressFormat.JPEG, 82, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

@Composable
fun WakeScreen(act: WakeActivity) {
    val ticket = remember { Api.epoch.capture() }
    val generation by Wake.generation.collectAsState()
    LaunchedEffect(generation) { if (generation != ticket) act.finish() }
    val p = P
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by Wake.session.collectAsState()
    val offline by Wake.offline.collectAsState()
    var mode by remember { mutableStateOf("home") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    val answers = remember { mutableStateListOf("", "", "", "", "") }
    var logicAns by remember { mutableStateOf("") }
    var readStart by remember { mutableStateOf(0L) }
    var err by remember { mutableStateOf<String?>(null) }
    val tick = rememberTick(1000)
    act.mode = mode
    VisibleEffect(Unit) {
        while (true) {
            try { Wake.pull(ticket) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { Api.epoch.commit(ticket) { Wake.offline.value = true } }
            delay(5000)
        }
    }
    val phase = if (offline && Wake.localRinging()) "offline" else Wake.phase(s)
    LaunchedEffect(phase) { if (phase == "checkin" && mode != "result" && mode != "override") mode = "logic"; if (phase == "offline") mode = "offline" }
    BackHandler(enabled = phase in setOf("ring", "checkin", "offline")) { mode = if (mode == "questions") "read" else "home" }

    fun submit(body: JSONObject) {
        busy = true
        scope.launch {
            val r = runCatching { Wake.request(ticket, "/v1/wake/proof", "POST", body.put("sid", s?.str("id").orEmpty())) }
            busy = false
            r.onSuccess { o ->
                result = o; mode = "result"
                o.optJSONObject("session").let { Wake.session.value = it }
                if (o.optBoolean("ok")) { Wake.quietUntil = 0; WakeService.start(ctx, "check") }
            }.onFailure { result = JSONObject().put("ok", false).put("reason", it.message ?: "Network error"); mode = "result" }
        }
    }

    val photoFile = remember { File(mediaDir(ctx), "wake.jpg") }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && photoFile.length() > 0) {
            busy = true
            scope.launch {
                val b64 = runCatching { withContext(Dispatchers.Default) { shrink(photoFile) } }.getOrElse { busy = false; err = "Couldn't read the photo: ${it.message}"; return@launch }
                photoFile.delete()
                submit(JSONObject().put("kind", "photo").put("image", b64))
            }
        } else { mode = "home"; err = "No photo taken." }
    }
    fun openCamera() {
        act.unlockThen {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", photoFile)
            runCatching { camera.launch(uri) }.onFailure { err = "Camera didn't open: ${it.message}" }
        }
    }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) openCamera() else err = "Camera permission is off. Use another proof, or allow Camera in Android app settings."
    }
    val stepPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { err = null; mode = "steps" } else err = "Physical activity permission is off. Use another proof, or allow it in Android app settings."
    }
    val hasCamera = remember { ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    val hasStepSensor = remember { ctx.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null }

    BoxWithConstraints(Modifier.fillMaxSize().background(p.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        val compact = maxHeight < 480.dp || LocalConfiguration.current.fontScale > 1.3f
        val showClock = maxHeight >= 240.dp
        Column(Modifier.fillMaxSize()) {
            // Give proofs and safety actions the space when landscape + IME leaves little height.
            if (showClock) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(SimpleDateFormat("HH:mm", LocalConfiguration.current.locales[0]).format(Date(tick)), style = serif(if (compact) 24f else if (mode == "home") 64f else 36f, p.tx))
                val tag = when (phase) { "ring" -> "ALARM"; "checkin" -> "CHECK-IN"; "wait" -> "UP"; "done" -> "DONE"; "offline" -> "OFFLINE"; else -> "" }
                if (tag.isNotEmpty()) Pill(tag, if (phase == "ring" || phase == "offline") p.att else p.acct, if (phase == "ring" || phase == "offline") p.attd else p.accd)
            }
            val content = s?.optJSONObject("content")
            Box(Modifier.weight(1f)) {
                when {
                    mode == "result" && result != null -> ResultView(result!!, s) { mode = if (result!!.optBoolean("ok")) "home" else (if (phase == "checkin") "logic" else "home") }
                    phase == "offline" -> OfflineView()
                    mode == "override" && (phase == "ring" || phase == "checkin") -> OverrideView(act) { mode = "home" }
                    mode == "steps" && phase == "ring" -> StepsView(s?.optInt("steps_needed", 120) ?: 120, busy, onBack = { mode = "home" }) { ts ->
                        submit(JSONObject().put("kind", "steps").put("steps", JSONArray(ts)))
                    }
                    phase == "checkin" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
                        Text("Check-in", style = sans(15f, 600, p.mu))
                        Spacer(Modifier.height(8.dp))
                        Text(s?.str("logic").orEmpty().ifBlank { "Loading the puzzle…" }, style = sans(20f, 500, p.tx, 27f))
                        Spacer(Modifier.height(16.dp))
                        val go = { if (!busy && logicAns.isNotBlank()) submit(JSONObject().put("kind", "logic").put("answer", logicAns)) }
                        AnswerBox(logicAns, { logicAns = it; Wake.touch() }, Modifier.fillMaxWidth(), onDone = go)
                        Spacer(Modifier.height(10.dp))
                        Btn(if (busy) "…" else "Submit", Modifier.fillMaxWidth(), enabled = !busy && logicAns.isNotBlank(), onClick = go)
                        val misses = s?.optInt("checkin_misses") ?: 0
                        if (misses > 0) { Spacer(Modifier.height(12.dp)); Text("Missed $misses check-in${if (misses > 1) "s" else ""}. Escalation at ${hhmm(s?.optDouble("escalate_at") ?: 0.0)}.", style = sans(13.5f, 500, p.att)) }
                        Spacer(Modifier.height(30.dp))
                        Btn("Emergency stop", Modifier.fillMaxWidth(), primary = false, danger = true) { mode = "override" }
                    }
                    phase == "wait" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
                        Text("You're up.", style = serif(40f, p.tx))
                        Spacer(Modifier.height(8.dp))
                        Text("Check-in at ${hhmm(s?.optDouble("checkin_due") ?: 0.0)}. Stay out of bed: if you miss it, the alarm comes back.", style = sans(16f, 400, p.tx2, 23f))
                        Spacer(Modifier.height(22.dp))
                        Btn("Close", Modifier.fillMaxWidth(), primary = false) { act.finish() }
                    }
                    phase == "done" || phase == "idle" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
                        Text("Good morning.", style = serif(40f, p.tx))
                        Spacer(Modifier.height(8.dp))
                        Text("All done. The summary and corrections are in the Wake chat.", style = sans(16f, 400, p.tx2, 23f))
                        Spacer(Modifier.height(22.dp))
                        Btn("Close", Modifier.fillMaxWidth()) { act.finish() }
                    }
                    mode == "read" && content != null -> {
                        val sc = rememberScrollState()
                        val atEnd = sc.maxValue > 0 && sc.value >= sc.maxValue - 40 || sc.maxValue == 0
                        val left = 45 - ((tick - readStart) / 1000).toInt()
                        Column(Modifier.fillMaxSize()) {
                            Column(Modifier.weight(1f).verticalScroll(sc).padding(horizontal = 22.dp, vertical = 12.dp)) {
                                Text(content.str("title"), style = serif(32f, p.tx))
                                Spacer(Modifier.height(12.dp))
                                Md(content.str("reading"), size = 16.5f)
                                Spacer(Modifier.height(30.dp))
                            }
                            Box(Modifier.padding(16.dp)) {
                                Btn(when { left > 0 -> "Questions in ${left}s"; !atEnd -> "Scroll to the end"; else -> "Questions" }, Modifier.fillMaxWidth(), enabled = left <= 0 && atEnd) { mode = "questions" }
                            }
                        }
                    }
                    mode == "questions" && content != null -> {
                        val qs = content.optJSONArray("questions").strs()
                        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                            item { Text("Answer in your own words. Being right isn't required, being awake is.", style = sans(14f, 500, p.mu, 20f), modifier = Modifier.padding(vertical = 10.dp)) }
                            qs.forEachIndexed { i, q ->
                                item {
                                    Text("${i + 1}. $q", style = sans(16f, 600, p.tx, 22f), modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                                    InputBox(answers[i], { answers[i] = it }, "Your answer", Modifier.fillMaxWidth(), minLines = 2)
                                }
                            }
                            item {
                                Row(Modifier.padding(vertical = 18.dp)) {
                                    Btn("Back to text", Modifier.weight(1f), primary = false) { mode = "read" }
                                    Spacer(Modifier.width(10.dp))
                                    Btn(if (busy) "Checking…" else "Submit", Modifier.weight(1f), enabled = !busy && answers.count { it.isNotBlank() } >= 4) {
                                        submit(JSONObject().put("kind", "read").put("answers", JSONArray(answers.toList())))
                                    }
                                }
                            }
                        }
                    }
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Text("Prove you're awake", style = sans(20f, 600, p.tx), modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp))
                        val fire = s?.optDouble("fire") ?: 0.0
                        Text("Re-rings until one proof passes. Escalation to your contacts at ${hhmm(s?.optDouble("escalate_at") ?: 0.0)}."
                            + if (s?.optBoolean("test") == true) " (Test: escalation goes to you only.)" else "",
                            style = sans(14f, 400, p.mu, 20f), modifier = Modifier.padding(start = 4.dp, bottom = 16.dp))
                        ProofCard("Read · 2 min", if (content != null) content.str("title") else "Preparing today's reading…", content != null) {
                            readStart = System.currentTimeMillis(); Wake.touch(); mode = "read"
                        }
                        ProofCard("Bathroom photo", if (hasCamera) "Take a fresh photo of the sink or mirror. The photo uploads to your gateway and its vision provider for checking." else "This device has no camera. Use another proof.", hasCamera) {
                            Wake.touch(150_000); err = null
                            if (ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
                            else act.unlockThen { camPerm.launch(Manifest.permission.CAMERA) }
                        }
                        ProofCard("Walk ${s?.optInt("steps_needed", 120) ?: 120} steps", if (hasStepSensor) "The phone counts your steps and sends their timestamps to your gateway to check the proof." else "This phone has no step sensor.", hasStepSensor) {
                            err = null
                            if (ctx.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) mode = "steps"
                            else act.unlockThen { stepPerm.launch(Manifest.permission.ACTIVITY_RECOGNITION) }
                        }
                        if (busy) Text("Checking…", style = sans(14f, 500, p.mu), modifier = Modifier.padding(16.dp))
                        err?.let { Text(it, style = sans(14f, 500, p.att, 20f), modifier = Modifier.padding(16.dp)) }
                        if (fire > 0) Text("Alarm ${hhmm(fire)} · rang ${s?.optInt("rings") ?: 1}×", style = mono(12f, 500, p.dim), modifier = Modifier.padding(16.dp))
                        Spacer(Modifier.height(16.dp))
                        Btn("Emergency stop", Modifier.fillMaxWidth(), primary = false, danger = true) { mode = "override" }
                    }
                }
            }
        }
    }
}

@Composable
fun OverrideView(act: WakeActivity, onBack: () -> Unit) {
    val ticket = remember { Api.epoch.capture() }
    val p = P
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var sure by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        Text("Emergency stop", style = serif(36f, p.att))
        Spacer(Modifier.height(6.dp))
        Text("Only for when you really can't do a proof. It ends this alarm with no check-in, and nobody is messaged.", style = sans(15f, 400, p.tx2, 21f))
        Spacer(Modifier.height(18.dp))
        if (!sure) {
            Text("Password", style = sans(14f, 600, p.mu))
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.text.BasicTextField(pw, { pw = it; err = null }, singleLine = true, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Emergency stop password" },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                textStyle = mono(18f, 500, p.tx), cursorBrush = androidx.compose.ui.graphics.SolidColor(p.acct),
                decorationBox = { inner -> Box(Modifier.clip(RoundedCornerShape(16.dp)).background(p.s2).border(1.dp, p.line, RoundedCornerShape(16.dp)).padding(14.dp)) { inner() } })
            err?.let { Spacer(Modifier.height(8.dp)); Text(it, style = sans(14f, 500, p.att)) }
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("Back", Modifier.fillMaxWidth(), primary = false, onClick = onBack)
                Btn("Continue", Modifier.fillMaxWidth(), danger = true, enabled = pw.isNotEmpty()) { sure = true }
            }
        } else {
            Text("Are you sure?", style = sans(22f, 650, p.tx))
            Spacer(Modifier.height(6.dp))
            Text("The alarm stops now and today's wake-up won't count.", style = sans(15f, 400, p.tx2, 21f))
            err?.let { Spacer(Modifier.height(8.dp)); Text(it, style = sans(14f, 500, p.att)) }
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("No, keep it", Modifier.fillMaxWidth(), primary = true, enabled = !busy) { sure = false; pw = ""; onBack() }
                Btn(if (busy) "…" else "Yes, stop", Modifier.fillMaxWidth(), danger = true, enabled = !busy) {
                    busy = true
                    scope.launch {
                        val r = runCatching { Wake.request(ticket, "/v1/wake/override", "POST", JSONObject().put("password", pw)) }
                        busy = false
                        r.onSuccess { o ->
                            if (o.optBoolean("ok")) { Wake.session.value = null; Wake.markLocalFire(ok = true); WakeService.start(ctx, "check"); act.finish() }
                            else { err = o.str("reason"); sure = false; pw = "" }
                        }.onFailure { err = it.message; sure = false }
                    }
                }
            }
        }
    }
}

@Composable
fun StepsView(need: Int, busy: Boolean, onBack: () -> Unit, onDone: (List<Double>) -> Unit) {
    val p = P
    val ctx = LocalContext.current
    val steps = remember { mutableStateListOf<Double>() }
    var sent by remember { mutableStateOf(false) }
    var sensorError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                // event.timestamp is when the step happened (elapsed-realtime ns); events arrive batched, so don't use arrival time
                val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000L
                steps.add((System.currentTimeMillis() - ageMs.coerceAtLeast(0L)) / 1000.0)
                Wake.touch(30_000)
                if (steps.size >= need && !sent) { sent = true; onDone(steps.toList()) }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        val registered = runCatching {
            sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_NORMAL) } == true
        }.getOrDefault(false)
        if (registered) Wake.touch(30_000)
        else sensorError = "Step counting is unavailable or permission was revoked. Go back and choose another proof."
        onDispose { sm?.unregisterListener(l) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(30.dp))
        Text("${steps.size}", style = serif(120f, p.tx))
        Text("of $need steps", style = sans(18f, 500, p.mu))
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(p.s2)) {
            Box(Modifier.fillMaxWidth((steps.size.toFloat() / need).coerceIn(0f, 1f)).height(10.dp).clip(RoundedCornerShape(5.dp)).background(p.acc))
        }
        Spacer(Modifier.height(20.dp))
        Text(sensorError ?: if (busy || sent) "Checking…" else "Keep walking. The alarm stays quiet while you move and comes back 30 s after you stop.",
            style = sans(15f, 400, p.tx2, 21f))
        Spacer(Modifier.height(24.dp))
        Btn("Back", Modifier.fillMaxWidth(), primary = false, onClick = onBack)
    }
}

@Composable
fun ProofCard(title: String, sub: String, enabled: Boolean, on: () -> Unit) {
    val p = P
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(22.dp)).background(if (enabled) p.s1 else p.bg)
        .border(1.dp, if (enabled) p.line2 else p.line, RoundedCornerShape(22.dp)).clickable(enabled = enabled, onClick = on).padding(18.dp)) {
        Text(title, style = sans(18f, 650, if (enabled) p.tx else p.dim))
        Spacer(Modifier.height(3.dp))
        Text(sub, style = sans(14f, 400, if (enabled) p.mu else p.dim, 20f))
    }
}

@Composable
fun ResultView(r: JSONObject, s: JSONObject?, onBack: () -> Unit) {
    val p = P
    val ok = r.optBoolean("ok")
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        item {
            Text(if (ok) "Accepted." else "Not yet.", style = serif(40f, if (ok) p.tx else p.att), modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(6.dp))
            Text(r.str("reason"), style = sans(15f, 400, p.tx2, 21f))
            if (r.has("note")) { Spacer(Modifier.height(10.dp)); Text(r.str("note"), style = sans(15f, 500, p.tx, 21f)) }
        }
        val fb = r.optJSONArray("feedback").objs()
        val qs = s?.optJSONObject("content")?.optJSONArray("questions").strs()
        fb.forEachIndexed { i, f ->
            item {
                Column(Modifier.padding(top = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val v = f.str("verdict")
                        Pill(v.ifBlank { "–" }, if (v == "correct") p.good else if (v == "partial") p.acct else p.att, if (v == "correct") p.s2 else if (v == "partial") p.accd else p.attd)
                        Spacer(Modifier.width(8.dp))
                        Text(qs.getOrNull(i).orEmpty(), style = sans(14f, 600, p.tx, 19f), modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(f.str("note"), style = sans(14.5f, 400, p.tx2, 20f))
                }
            }
        }
        item {
            Spacer(Modifier.height(18.dp))
            val ph = Wake.phase(r.optJSONObject("session"))
            if (ok && ph == "wait") Text("Check-in at ${hhmm(r.optJSONObject("session")?.optDouble("checkin_due") ?: 0.0)}.", style = sans(15f, 600, p.acct))
            Spacer(Modifier.height(12.dp))
            Btn(if (ok) "OK" else "Try again", Modifier.fillMaxWidth(), primary = ok, onClick = onBack)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun OfflineView() {
    val p = P
    var pz by remember { mutableStateOf(Wake.localPuzzle()) }
    var a by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        Text("No connection to Hermes.", style = sans(18f, 600, p.tx))
        Text("Solve this to quiet the alarm here. The server still expects a real proof once you're back online.", style = sans(14f, 400, p.mu, 20f))
        Spacer(Modifier.height(18.dp))
        Text(pz.first, style = mono(30f, 600, p.tx))
        Spacer(Modifier.height(12.dp))
        InputBox(a, { a = it.filter(Char::isDigit) }, "Answer", Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Btn("Check", Modifier.fillMaxWidth(), enabled = a.isNotBlank()) {
            if (a.toIntOrNull() == pz.second) { Wake.markLocalFire(ok = true); Wake.quietUntil = 0 } else { Ui.say("Wrong, new one"); pz = Wake.localPuzzle(); a = "" }
        }
    }
}

// ---------------------------------------------------------------- settings
private val DAYS = listOf(6 to "Sunday", 0 to "Monday", 1 to "Tuesday", 2 to "Wednesday", 3 to "Thursday", 4 to "Friday", 5 to "Saturday")

@Composable
fun WakeSettingsScreen() {
    val ticket = remember { Api.epoch.capture() }
    val p = P
    val locale = LocalConfiguration.current.locales[0]
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ov by Wake.overview.collectAsState()
    val feat by Hub.features.collectAsState()
    var addName by remember { mutableStateOf("") }
    var addPhone by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var wa by remember { mutableStateOf("") }
    var permissionRevision by remember { mutableStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permissionRevision++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val access = remember(permissionRevision) { listOf(Wake.canExact(ctx), Wake.notificationsReady(ctx), Wake.canFullScreen(ctx),
        ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)) }
    val proofPermissions = remember {
        buildList {
            if (ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) add(Manifest.permission.CAMERA)
            if (ctx.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null) add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }
    val proofsGranted = remember(permissionRevision) { proofPermissions.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } }
    val askProofs = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        permissionRevision++
        if (r.values.any { !it }) Ui.say("You can use reading proof without these permissions. Enable camera or activity in Android app settings to use those proofs.")
    }
    fun settings(i: Intent) {
        runCatching { ctx.startActivity(i) }.onFailure {
            runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) }
                .onFailure { Ui.say("Open Android Settings > Apps > Hermes to change this permission.") }
        }
    }
    LaunchedEffect(Unit) {
        runCatching { Wake.refresh(ctx, ticket) }.onSuccess { owner = it.optJSONObject("config")?.str("owner_phone").orEmpty(); wa = it.str("whatsapp") }
            .onFailure { Ui.say(it.message) }
    }
    val cfg = ov?.optJSONObject("config") ?: JSONObject()
    val alarms = cfg.optJSONArray("alarms").objs()
    val contacts = cfg.optJSONArray("contacts").objs()
    fun save(mut: (JSONObject) -> Unit) {
        val c = JSONObject().put("alarms", JSONArray(alarms.map { JSONObject(it.toString()) })).put("contacts", JSONArray(contacts.map { JSONObject(it.toString()) }))
            .put("owner_phone", owner)
        mut(c)
        scope.launch { runCatching { Wake.save(ctx, c, ticket) }.onFailure { Ui.say(it.message) } }
    }
    val next = ov?.optJSONObject("next")
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar("Wake alarm", next?.let { "next " + SimpleDateFormat("EEE HH:mm", locale).format(Date((it.getDouble("ts") * 1000).toLong())) + " · " + until(it.getDouble("ts")) } ?: "no alarm set")
        LazyColumn(Modifier.fillMaxSize()) {
            if (!access[0]) item {
                Panel(Modifier.padding(top = 12.dp), border = p.att) {
                    SettingRow("Exact alarms are off", "Local alarms are not scheduled. Allow exact alarms before relying on Wake.") {
                        Btn("Allow", primary = false) { settings(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName))) }
                    }
                }
            }
            if (!access[1]) item {
                Panel(Modifier.padding(top = 12.dp), border = p.att) {
                    SettingRow("Alarm notifications need attention", "Allow Hermes notifications and set the Wake alarm channel to high importance so the proof screen is reachable.") {
                        Btn("Settings", primary = false) {
                            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
                            settings(if (!nm.areNotificationsEnabled()) Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            else Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, Wake.CH))
                        }
                    }
                }
            }
            if (!access[2] && android.os.Build.VERSION.SDK_INT >= 34) item {
                Panel(Modifier.padding(top = 12.dp), border = p.att) {
                    SettingRow("Full-screen alarms are off", "Wake can ring, but Android may only show a notification. Allow full-screen alarms to show proofs over the lock screen.") {
                        Btn("Allow", primary = false) { settings(Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + ctx.packageName))) }
                    }
                }
            }
            if (!access[3]) item {
                Panel(Modifier.padding(top = 12.dp)) {
                    SettingRow("Battery optimization is on", "Exact alarms can still fire. Background syncing may be delayed; review battery settings if reminders arrive late.") {
                        Btn("Review", primary = false) { settings(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }
                }
            }
            item { Section("Alarms", alarms.size.toString()) }
            item {
                Panel {
                    alarms.forEachIndexed { i, a ->
                        if (i > 0) Line()
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(a.str("time"), style = mono(34f, 600, if (a.optBoolean("on", true)) p.tx else p.dim), modifier = Modifier.clickable {
                                    val (h, m) = a.str("time").split(":").map { it.toInt() }
                                    TimePickerDialog(ctx, { _, hh, mm -> save { c -> c.getJSONArray("alarms").getJSONObject(i).put("time", "%02d:%02d".format(Locale.US, hh, mm)) } }, h, m, true).show()
                                })
                                Spacer(Modifier.weight(1f))
                                Toggle(a.optBoolean("on", true), { v -> save { c -> c.getJSONArray("alarms").getJSONObject(i).put("on", v) } })
                                Spacer(Modifier.width(4.dp))
                                IconBtn(Ic.x, p.mu, 40.dp) { save { c -> c.getJSONArray("alarms").remove(i) } }
                            }
                            Spacer(Modifier.height(8.dp))
                            val days = a.optJSONArray("days").strs().mapNotNull { it.toIntOrNull() }.toSet()
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                DAYS.forEach { (d, day) ->
                                    val on = d in days
                                    Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(RoundedCornerShape(16.dp))
                                        .background(if (on) p.acc else p.s2).selectable(selected = on, role = Role.Button, onClick = {
                                            val nd = if (on) days - d else days + d
                                            save { c -> c.getJSONArray("alarms").getJSONObject(i).put("days", JSONArray(nd.sorted())) }
                                        }).padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                                        Text(day, style = sans(13.5f, 650, if (on) p.ink else p.tx2))
                                    }
                                }
                            }
                            if (days.isEmpty()) Text("Once", style = mono(12f, 500, p.mu), modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    if (alarms.isNotEmpty()) Line()
                    Row(Modifier.fillMaxWidth().clickable {
                        val now = Calendar.getInstance()
                        TimePickerDialog(ctx, { _, hh, mm ->
                            save { c -> c.getJSONArray("alarms").put(JSONObject().put("time", "%02d:%02d".format(Locale.US, hh, mm)).put("on", true).put("days", JSONArray())) }
                        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
                    }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icn(Ic.plus, p.acct, 20.dp); Spacer(Modifier.width(10.dp)); Text("Add alarm", style = sans(15f, 600, p.acct))
                    }
                }
            }
            item {
                Text("Re-ring at +${cfg.optInt("recheck_min", 10)} min with no proof · check-in at +${cfg.optInt("checkin_min", 15)}" +
                    (if (feat.wakeWa) " · WhatsApp at +${cfg.optInt("escalate_min", 30)}" else ""),
                    style = mono(11.5f, 500, p.mu), modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
            }
            item { Section("Reading level", null) }
            item {
                val lv = ov?.optInt("level", 1) ?: 1
                val lmax = ov?.optInt("level_max", 8) ?: 8
                val auto = cfg.optBoolean("level_auto", true)
                val nextLv = ov?.optDouble("level_next", 0.0) ?: 0.0
                Panel {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Step $lv of $lmax", style = sans(17f, 650, p.tx))
                            Text(when { lv <= 3 -> "Beginner: short, plain words, easy questions"; lv <= 6 -> "Intermediate: one mechanism, why-questions"; else -> "Advanced: dense, reasoning questions" },
                                style = sans(13f, 400, p.mu, 18f))
                        }
                        IconBtn(Ic.back, p.tx, 40.dp, p.s2, enabled = lv > 1) { save { c -> c.put("level_base", lv - 1) } }
                        Spacer(Modifier.width(8.dp))
                        IconBtn(Ic.chevR, p.tx, 40.dp, p.s2, enabled = lv < lmax) { save { c -> c.put("level_base", lv + 1) } }
                    }
                    Line()
                    SettingRow("Harder every 2 weeks", if (auto && nextLv > 0) "Next step " + SimpleDateFormat("EEE d MMM", locale).format(Date((nextLv * 1000).toLong()))
                        else if (auto) "At the top step" else "Off: stays on step $lv") {
                        Toggle(auto, { v -> save { c -> c.put("level_auto", v) } })
                    }
                }
            }
            if (feat.wakeWa) {
            item { Section("Escalation", if (wa.isBlank()) null else "whatsapp $wa", if (wa == "connected") p.good else p.mu) }
            item {
                Panel {
                    contacts.forEachIndexed { i, c ->
                        if (i > 0) Line()
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.str("name").ifBlank { "Contact" }, style = sans(15f, 600, p.tx))
                                Text(c.str("phone"), style = mono(12f, 500, p.mu))
                            }
                            IconBtn(Ic.x, p.mu, 40.dp) { save { o -> o.getJSONArray("contacts").remove(i) } }
                        }
                    }
                    if (contacts.isNotEmpty()) Line()
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InputBox(addName, { addName = it }, "Name", Modifier.fillMaxWidth())
                        BasicPhone(addPhone, { addPhone = it }, "Phone, e.g. 050-1234567")
                        Btn("Add contact", Modifier.fillMaxWidth(), primary = false, enabled = addPhone.count(Char::isDigit) >= 9) {
                            save { o -> o.getJSONArray("contacts").put(JSONObject().put("name", addName.trim()).put("phone", addPhone.trim())) }
                            addName = ""; addPhone = ""
                        }
                    }
                }
            }
            }
            item { Section("Emergency stop", null) }
            item {
                var oldPw by remember { mutableStateOf("") }
                var newPw by remember { mutableStateOf("") }
                Panel {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("The alarm screen has an emergency stop behind a password and an are-you-sure. Change the password here.", style = sans(13f, 400, p.mu, 18f))
                        PwField(oldPw, { oldPw = it }, "Current password")
                        PwField(newPw, { newPw = it }, "New password (8+ characters)")
                        Btn("Change password", Modifier.fillMaxWidth(), primary = false, enabled = oldPw.isNotEmpty() && newPw.length >= 8) {
                            scope.launch {
                                runCatching { Wake.request(ticket, "/v1/wake/password", "POST", JSONObject().put("old", oldPw).put("new", newPw)) }
                                    .onSuccess { Ui.say(if (it.optBoolean("ok")) "Password changed" else it.str("reason")); if (it.optBoolean("ok")) { oldPw = ""; newPw = "" } }
                                    .onFailure { Ui.say(it.message) }
                            }
                        }
                    }
                }
            }
            if (!proofsGranted) item {
                Section("Optional proof permissions", null)
                Panel {
                    SettingRow("Photo and step proofs", "Only supported hardware needs permission. Reading proof works without camera or activity access.") {
                        Btn("Allow", primary = false) { askProofs.launch(proofPermissions.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()) }
                    }
                    Line()
                    SettingRow("Previously denied?", "Android may stop showing permission prompts. You can change them in app settings.") {
                        Btn("Settings", primary = false) { settings(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) }
                    }
                }
            }
            item { Section("Testing", null) }
            item {
                Panel {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (feat.wakeWa) {
                            Text("Your own number. Test escalations go here instead of your contacts.", style = sans(13f, 400, p.mu, 18f))
                            BasicPhone(owner, { owner = it }, "Your number")
                            Btn("Save number", Modifier.fillMaxWidth(), primary = false, enabled = owner.count(Char::isDigit) >= 9) { save { } }
                            Btn("Send WhatsApp test to me", Modifier.fillMaxWidth(), primary = false) {
                                scope.launch { runCatching { Wake.request(ticket, "/v1/wake/whatsapp-test", "POST") }.onSuccess { Ui.say(if (it.optBoolean("ok")) "Sent" else it.str("error")) }.onFailure { Ui.say(it.message) } }
                            }
                        }
                        Btn("Test the alarm now", Modifier.fillMaxWidth()) {
                            scope.launch {
                                runCatching { Wake.request(ticket, "/v1/wake/test", "POST") }.onSuccess { Wake.session.value = it.optJSONObject("session"); WakeService.start(ctx, "check") }.onFailure { Ui.say(it.message) }
                            }
                        }
                        Btn("Cancel a running test", Modifier.fillMaxWidth(), danger = true) {
                            scope.launch { runCatching { Wake.request(ticket, "/v1/wake/cancel", "POST") }.onSuccess { WakeService.start(ctx, "check") }.onFailure { Ui.say(it.message) } }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun BasicPhone(v: String, on: (String) -> Unit, ph: String) {
    val p = P
    androidx.compose.foundation.text.BasicTextField(v, { on(it.filter { c -> c.isDigit() || c in "+- " }) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), textStyle = mono(16f, 500, p.tx),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(p.acct), modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(Modifier.clip(RoundedCornerShape(22.dp)).background(p.s2).border(1.dp, p.line, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (v.isEmpty()) Text(ph, style = mono(16f, 400, p.dim)); inner()
            }
        })
}

@Composable
private fun AnswerBox(v: String, on: (String) -> Unit, modifier: Modifier, onDone: () -> Unit) {
    val p = P
    androidx.compose.foundation.text.BasicTextField(v, on, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }),
        textStyle = sans(17f, 500, p.tx), cursorBrush = androidx.compose.ui.graphics.SolidColor(p.acct),
        decorationBox = { inner ->
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(p.s2).border(1.dp, p.line, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (v.isEmpty()) Text("Your answer", style = sans(17f, 400, p.dim)); inner()
            }
        })
}

@Composable
private fun PwField(v: String, on: (String) -> Unit, ph: String) {
    val p = P
    androidx.compose.foundation.text.BasicTextField(v, on, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        textStyle = mono(16f, 500, p.tx), cursorBrush = androidx.compose.ui.graphics.SolidColor(p.acct),
        decorationBox = { inner ->
            Box(Modifier.clip(RoundedCornerShape(22.dp)).background(p.s2).border(1.dp, p.line, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (v.isEmpty()) Text(ph, style = mono(16f, 400, p.dim)); inner()
            }
        })
}
