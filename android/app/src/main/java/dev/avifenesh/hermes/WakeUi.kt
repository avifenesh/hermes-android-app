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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import java.util.Calendar
import java.util.Locale

/** Full-screen alarm surface over the lock screen. Nothing here stops the alarm except a proof the server accepts. */
class WakeActivity : FragmentActivity() {
    var mode = "home"

    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(Locales.wrap(newBase)) }

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

private fun hhmm(ts: Double): String = if (ts <= 0) "–" else Fmt.time((ts * 1000).toLong())

private fun shrink(f: File): String {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 1280) s *= 2
    val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: error(str(R.string.wake_photo_unreadable))
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
            }.onFailure { result = JSONObject().put("ok", false).put("reason", it.message ?: str(R.string.wake_network_error)); mode = "result" }
        }
    }

    val photoFile = remember { File(mediaDir(ctx), "wake.jpg") }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && photoFile.length() > 0) {
            busy = true
            scope.launch {
                val b64 = runCatching { withContext(Dispatchers.Default) { shrink(photoFile) } }.getOrElse { busy = false; err = str(R.string.wake_photo_read_failed, it.message?.let(::bidiIsolate)); return@launch }
                photoFile.delete()
                submit(JSONObject().put("kind", "photo").put("image", b64))
            }
        } else { mode = "home"; err = str(R.string.wake_no_photo) }
    }
    fun openCamera() {
        act.unlockThen {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", photoFile)
            runCatching { camera.launch(uri) }.onFailure { err = str(R.string.wake_camera_failed, it.message?.let(::bidiIsolate)) }
        }
    }
    // Full asks for its declared CAMERA grant first; Play declares none and opens the camera app directly.
    val cameraStart = rememberCameraStart(open = ::openCamera,
        denied = { err = str(R.string.wake_camera_permission_off) })
    val stepPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { err = null; mode = "steps" } else err = str(R.string.wake_activity_permission_off)
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
                val now = Fmt.time(tick)
                Text(now, style = serif(if (compact) 24f else if (mode == "home") 64f else 36f, p.tx).trackFor(now))
                val tag = when (phase) { "ring" -> R.string.wake_tag_alarm; "checkin" -> R.string.wake_tag_checkin; "wait" -> R.string.wake_tag_up; "done" -> R.string.wake_tag_done; "offline" -> R.string.wake_tag_offline; else -> 0 }
                if (tag != 0) Pill(stringResource(tag), if (phase == "ring" || phase == "offline") p.att else p.acct, if (phase == "ring" || phase == "offline") p.attd else p.accd)
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
                        Text(stringResource(R.string.wake_checkin), style = sans(15f, 600, p.mu))
                        Spacer(Modifier.height(8.dp))
                        Text(s?.str("logic").orEmpty().ifBlank { stringResource(R.string.wake_loading_puzzle) }, style = sans(20f, 500, p.tx, 27f).let { if (s?.str("logic").isNullOrBlank()) it else it.content() })
                        Spacer(Modifier.height(16.dp))
                        val go = { if (!busy && logicAns.isNotBlank()) submit(JSONObject().put("kind", "logic").put("answer", logicAns)) }
                        AnswerBox(logicAns, { logicAns = it; Wake.touch() }, Modifier.fillMaxWidth(), onDone = go)
                        Spacer(Modifier.height(10.dp))
                        Btn(if (busy) "…" else stringResource(R.string.wake_submit), Modifier.fillMaxWidth(), enabled = !busy && logicAns.isNotBlank(), onClick = go)
                        val misses = s?.optInt("checkin_misses") ?: 0
                        if (misses > 0) { Spacer(Modifier.height(12.dp)); Text(pluralStringResource(R.plurals.wake_checkins_missed, misses, misses, hhmm(s?.optDouble("escalate_at") ?: 0.0)), style = sans(13.5f, 500, p.att)) }
                        Spacer(Modifier.height(30.dp))
                        Btn(stringResource(R.string.wake_emergency_stop), Modifier.fillMaxWidth(), primary = false, danger = true) { mode = "override" }
                    }
                    phase == "wait" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
                        Text(stringResource(R.string.wake_youre_up), style = serif(40f, p.tx).trackFor(stringResource(R.string.wake_youre_up)))
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.wake_checkin_at_stay_up, hhmm(s?.optDouble("checkin_due") ?: 0.0)), style = sans(16f, 400, p.tx2, 23f))
                        Spacer(Modifier.height(22.dp))
                        Btn(stringResource(R.string.common_close), Modifier.fillMaxWidth(), primary = false) { act.finish() }
                    }
                    phase == "done" || phase == "idle" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
                        Text(stringResource(R.string.wake_good_morning), style = serif(40f, p.tx).trackFor(stringResource(R.string.wake_good_morning)))
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.wake_all_done), style = sans(16f, 400, p.tx2, 23f))
                        Spacer(Modifier.height(22.dp))
                        Btn(stringResource(R.string.common_close), Modifier.fillMaxWidth()) { act.finish() }
                    }
                    mode == "read" && content != null -> {
                        val sc = rememberScrollState()
                        val atEnd = sc.maxValue > 0 && sc.value >= sc.maxValue - 40 || sc.maxValue == 0
                        val left = 45 - ((tick - readStart) / 1000).toInt()
                        Column(Modifier.fillMaxSize()) {
                            Column(Modifier.weight(1f).verticalScroll(sc).padding(horizontal = 22.dp, vertical = 12.dp)) {
                                Text(content.str("title"), style = serif(32f, p.tx).trackFor(content.str("title")).content())
                                Spacer(Modifier.height(12.dp))
                                Md(content.str("reading"), size = 16.5f)
                                Spacer(Modifier.height(30.dp))
                            }
                            Box(Modifier.padding(16.dp)) {
                                Btn(when { left > 0 -> stringResource(R.string.wake_questions_in, Fmt.seconds(left.toLong())); !atEnd -> stringResource(R.string.wake_scroll_to_end); else -> stringResource(R.string.wake_questions) }, Modifier.fillMaxWidth(), enabled = left <= 0 && atEnd) { mode = "questions" }
                            }
                        }
                    }
                    mode == "questions" && content != null -> {
                        val qs = content.optJSONArray("questions").strs()
                        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                            item { Text(stringResource(R.string.wake_answer_own_words), style = sans(14f, 500, p.mu, 20f), modifier = Modifier.padding(vertical = 10.dp)) }
                            qs.forEachIndexed { i, q ->
                                item {
                                    Text("${i + 1}. $q", style = sans(16f, 600, p.tx, 22f).content(), modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                                    InputBox(answers[i], { answers[i] = it }, stringResource(R.string.wake_your_answer), Modifier.fillMaxWidth(), minLines = 2)
                                }
                            }
                            item {
                                Row(Modifier.padding(vertical = 18.dp)) {
                                    Btn(stringResource(R.string.wake_back_to_text), Modifier.weight(1f), primary = false) { mode = "read" }
                                    Spacer(Modifier.width(10.dp))
                                    Btn(if (busy) stringResource(R.string.common_checking) else stringResource(R.string.wake_submit), Modifier.weight(1f), enabled = !busy && answers.count { it.isNotBlank() } >= 4) {
                                        submit(JSONObject().put("kind", "read").put("answers", JSONArray(answers.toList())))
                                    }
                                }
                            }
                        }
                    }
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Text(stringResource(R.string.wake_prove_awake), style = sans(20f, 600, p.tx), modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp))
                        val fire = s?.optDouble("fire") ?: 0.0
                        Text(stringResource(if (s?.optBoolean("test") == true) R.string.wake_rerings_test else R.string.wake_rerings, hhmm(s?.optDouble("escalate_at") ?: 0.0)),
                            style = sans(14f, 400, p.mu, 20f), modifier = Modifier.padding(start = 4.dp, bottom = 16.dp))
                        ProofCard(stringResource(R.string.wake_proof_read), if (content != null) content.str("title") else stringResource(R.string.wake_preparing_reading), content != null) {
                            readStart = System.currentTimeMillis(); Wake.touch(); mode = "read"
                        }
                        ProofCard(stringResource(R.string.wake_proof_photo), if (hasCamera) stringResource(R.string.wake_proof_photo_sub) else stringResource(R.string.wake_proof_no_camera), hasCamera) {
                            Wake.touch(150_000); err = null
                            act.unlockThen { cameraStart() }
                        }
                        ProofCard((s?.optInt("steps_needed", 120) ?: 120).let { pluralStringResource(R.plurals.wake_proof_walk, it, it) }, if (hasStepSensor) stringResource(R.string.wake_proof_steps_sub) else stringResource(R.string.wake_proof_no_step_sensor), hasStepSensor) {
                            err = null
                            if (ctx.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) mode = "steps"
                            else act.unlockThen { stepPerm.launch(Manifest.permission.ACTIVITY_RECOGNITION) }
                        }
                        if (busy) Text(stringResource(R.string.common_checking), style = sans(14f, 500, p.mu), modifier = Modifier.padding(16.dp))
                        err?.let { Text(it, style = sans(14f, 500, p.att, 20f), modifier = Modifier.padding(16.dp)) }
                        if (fire > 0) Text((s?.optInt("rings") ?: 1).let { pluralStringResource(R.plurals.wake_alarm_rang, it, hhmm(fire), it) }, style = mono(12f, 500, p.dim), modifier = Modifier.padding(16.dp))
                        Spacer(Modifier.height(16.dp))
                        Btn(stringResource(R.string.wake_emergency_stop), Modifier.fillMaxWidth(), primary = false, danger = true) { mode = "override" }
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
        Text(stringResource(R.string.wake_override_title), style = serif(36f, p.att).trackFor(stringResource(R.string.wake_override_title)))
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.wake_override_body), style = sans(15f, 400, p.tx2, 21f))
        Spacer(Modifier.height(18.dp))
        if (!sure) {
            Text(stringResource(R.string.wake_override_password), style = sans(14f, 600, p.mu))
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.text.BasicTextField(pw, { pw = it; err = null }, singleLine = true, modifier = Modifier.fillMaxWidth().semantics { contentDescription = str(R.string.wake_override_password_desc) },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                textStyle = mono(18f, 500, p.tx).ltr(), cursorBrush = androidx.compose.ui.graphics.SolidColor(p.acct),
                decorationBox = { inner -> Box(Modifier.clip(RoundedCornerShape(16.dp)).background(p.s2).border(1.dp, p.line, RoundedCornerShape(16.dp)).padding(14.dp)) { inner() } })
            err?.let { Spacer(Modifier.height(8.dp)); Text(it, style = sans(14f, 500, p.att)) }
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(stringResource(R.string.common_back), Modifier.fillMaxWidth(), primary = false, onClick = onBack)
                Btn(stringResource(R.string.wake_override_continue), Modifier.fillMaxWidth(), danger = true, enabled = pw.isNotEmpty()) { sure = true }
            }
        } else {
            Text(stringResource(R.string.wake_override_sure), style = sans(22f, 650, p.tx))
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.wake_override_sure_body), style = sans(15f, 400, p.tx2, 21f))
            err?.let { Spacer(Modifier.height(8.dp)); Text(it, style = sans(14f, 500, p.att)) }
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(stringResource(R.string.wake_override_keep), Modifier.fillMaxWidth(), primary = true, enabled = !busy) { sure = false; pw = ""; onBack() }
                Btn(if (busy) "…" else stringResource(R.string.wake_override_yes_stop), Modifier.fillMaxWidth(), danger = true, enabled = !busy) {
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
        else sensorError = str(R.string.wake_steps_unavailable)
        onDispose { sm?.unregisterListener(l) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(30.dp))
        Text(Fmt.int(steps.size), style = serif(120f, p.tx).trackFor(Fmt.int(steps.size)))
        Text(pluralStringResource(R.plurals.wake_steps_of, need, need), style = sans(18f, 500, p.mu))
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(p.s2)) {
            Box(Modifier.fillMaxWidth((steps.size.toFloat() / need).coerceIn(0f, 1f)).height(10.dp).clip(RoundedCornerShape(5.dp)).background(p.acc))
        }
        Spacer(Modifier.height(20.dp))
        Text(sensorError ?: if (busy || sent) stringResource(R.string.common_checking) else stringResource(R.string.wake_steps_keep_walking),
            style = sans(15f, 400, p.tx2, 21f))
        Spacer(Modifier.height(24.dp))
        Btn(stringResource(R.string.common_back), Modifier.fillMaxWidth(), primary = false, onClick = onBack)
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
            val verdict = stringResource(if (ok) R.string.wake_result_accepted else R.string.wake_result_not_yet)
            Text(verdict, style = serif(40f, if (ok) p.tx else p.att).trackFor(verdict), modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(6.dp))
            Text(r.str("reason"), style = sans(15f, 400, p.tx2, 21f).content())
            if (r.has("note")) { Spacer(Modifier.height(10.dp)); Text(r.str("note"), style = sans(15f, 500, p.tx, 21f).content()) }
        }
        val fb = r.optJSONArray("feedback").objs()
        val qs = s?.optJSONObject("content")?.optJSONArray("questions").strs()
        fb.forEachIndexed { i, f ->
            item {
                Column(Modifier.padding(top = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val v = f.str("verdict")
                        Pill(verdictLabel(v), if (v == "correct") p.good else if (v == "partial") p.acct else p.att, if (v == "correct") p.s2 else if (v == "partial") p.accd else p.attd)
                        Spacer(Modifier.width(8.dp))
                        Text(qs.getOrNull(i).orEmpty(), style = sans(14f, 600, p.tx, 19f).contentLine(), modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(f.str("note"), style = sans(14.5f, 400, p.tx2, 20f).content())
                }
            }
        }
        item {
            Spacer(Modifier.height(18.dp))
            val ph = Wake.phase(r.optJSONObject("session"))
            if (ok && ph == "wait") Text(stringResource(R.string.wake_result_checkin_at, hhmm(r.optJSONObject("session")?.optDouble("checkin_due") ?: 0.0)), style = sans(15f, 600, p.acct))
            Spacer(Modifier.height(12.dp))
            Btn(stringResource(if (ok) R.string.common_ok else R.string.common_try_again), Modifier.fillMaxWidth(), primary = ok, onClick = onBack)
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
        Text(stringResource(R.string.wake_offline_title, bidiIsolate(HERMES)), style = sans(18f, 600, p.tx))
        Text(stringResource(R.string.wake_offline_body), style = sans(14f, 400, p.mu, 20f))
        Spacer(Modifier.height(18.dp))
        Text(pz.first, style = mono(30f, 600, p.tx).ltr()) // arithmetic reads left to right
        Spacer(Modifier.height(12.dp))
        InputBox(a, { a = it.filter(Char::isDigit) }, stringResource(R.string.wake_offline_answer), Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Btn(stringResource(R.string.wake_offline_check), Modifier.fillMaxWidth(), enabled = a.isNotBlank()) {
            if (a.toIntOrNull() == pz.second) { Wake.markLocalFire(ok = true); Wake.quietUntil = 0 } else { Ui.say(R.string.wake_offline_wrong); pz = Wake.localPuzzle(); a = "" }
        }
    }
}

// ---------------------------------------------------------------- settings
// Server day index (0 = Monday) to the Calendar weekday whose name the chip shows in the app language.
private val DAYS = listOf(6 to Calendar.SUNDAY, 0 to Calendar.MONDAY, 1 to Calendar.TUESDAY, 2 to Calendar.WEDNESDAY,
    3 to Calendar.THURSDAY, 4 to Calendar.FRIDAY, 5 to Calendar.SATURDAY)

private fun dayName(calendarDay: Int): String = android.icu.text.DateFormatSymbols.getInstance(Fmt.locale())
    .getWeekdays(android.icu.text.DateFormatSymbols.STANDALONE, android.icu.text.DateFormatSymbols.WIDE)[calendarDay]

/** A stored "HH:mm" alarm time in the phone's 12/24-hour format; unparsable values show as stored. */
private fun alarmTime(t: String): String {
    val parts = t.split(":").map { it.toIntOrNull() }
    val h = parts.getOrNull(0); val m = parts.getOrNull(1)
    return if (parts.size == 2 && h != null && m != null) Fmt.time(h, m) else t
}

private fun verdictLabel(v: String): String = when (v) {
    "" -> "–"
    "correct" -> str(R.string.wake_verdict_correct)
    "partial" -> str(R.string.wake_verdict_partial)
    "wrong" -> str(R.string.wake_verdict_wrong)
    "blank" -> str(R.string.wake_verdict_blank)
    "unchecked" -> str(R.string.wake_verdict_unchecked)
    else -> v
}

@Composable
fun WakeSettingsScreen(modifier: Modifier = Modifier.fillMaxSize(), embedded: Boolean = false) {
    val ticket = remember { Api.epoch.capture() }
    val p = P
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ov by Wake.overview.collectAsState()
    val feat by Hub.features.collectAsState()
    var addName by remember { mutableStateOf("") }
    var addPhone by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var wa by remember { mutableStateOf("") }
    var permissionRevision by remember { mutableStateOf(0) }
    OnResume { permissionRevision++ }
    val access = remember(permissionRevision) { listOf(Wake.canExact(ctx), Wake.notificationsReady(ctx), Wake.canFullScreen(ctx),
        ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)) }
    val proofPermissions = remember {
        buildList {
            if (ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) addAll(CAMERA_PERMISSIONS)
            if (ctx.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null) add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }
    val proofsGranted = remember(permissionRevision) { proofPermissions.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } }
    val askProofs = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        permissionRevision++
        if (r.values.any { !it }) Ui.say(if (CAMERA_PERMISSIONS.isEmpty()) R.string.alarm_proof_permissions_denied_no_camera
            else R.string.alarm_proof_permissions_denied)
    }
    fun settings(i: Intent) {
        runCatching { ctx.startActivity(i) }.onFailure {
            runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) }
                .onFailure { Ui.say(R.string.alarm_open_app_settings, APP_NAME) }
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
    Column(modifier.imePadding()) {
        // Without exact alarm access nothing is scheduled, so the subtitle says that instead of a next ring time.
        if (!embedded) TopBar(stringResource(R.string.alarm_title), if (!access[0]) stringResource(R.string.alarm_exact_off) else next?.let { stringResource(R.string.alarm_next, Fmt.weekdayTime((it.getDouble("ts") * 1000).toLong()), until(it.getDouble("ts"))) } ?: stringResource(R.string.alarm_none_set))
        LazyColumn(Modifier.fillMaxSize()) {
            if (!access[0]) item {
                Panel(Modifier.padding(top = 12.dp), border = p.att) {
                    SettingRow(stringResource(R.string.alarm_exact_off), stringResource(WAKE_EXACT_OFF)) {
                        Btn(stringResource(R.string.common_allow), primary = false) { settings(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName))) }
                    }
                }
            }
            if (!access[1]) item {
                Panel(Modifier.padding(top = 12.dp), border = p.att) {
                    SettingRow(stringResource(R.string.alarm_notifications_attention), stringResource(R.string.alarm_notifications_attention_sub, bidiIsolate(APP_NAME))) {
                        Btn(stringResource(R.string.common_settings), primary = false) {
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
                    SettingRow(stringResource(R.string.alarm_fullscreen_off), stringResource(R.string.alarm_fullscreen_off_sub)) {
                        Btn(stringResource(R.string.common_allow), primary = false) { settings(Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + ctx.packageName))) }
                    }
                }
            }
            if (!access[3]) item {
                Panel(Modifier.padding(top = 12.dp)) {
                    SettingRow(stringResource(R.string.alarm_battery_on), stringResource(if (access[0]) R.string.alarm_battery_on_sub else R.string.alarm_battery_on_sync_sub)) {
                        Btn(stringResource(R.string.alarm_battery_review), primary = false) { settings(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }
                }
            }
            item { Section(stringResource(R.string.alarm_section_alarms), Fmt.int(alarms.size)) }
            item {
                Panel {
                    alarms.forEachIndexed { i, a ->
                        if (i > 0) Line()
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(alarmTime(a.str("time")), style = mono(34f, 600, if (a.optBoolean("on", true)) p.tx else p.dim), modifier = Modifier.clickable {
                                    val (h, m) = a.str("time").split(":").map { it.toInt() }
                                    TimePickerDialog(ctx, { _, hh, mm -> save { c -> c.getJSONArray("alarms").getJSONObject(i).put("time", "%02d:%02d".format(Locale.US, hh, mm)) } }, h, m, android.text.format.DateFormat.is24HourFormat(ctx)).show()
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
                                        Text(dayName(day), style = sans(13.5f, 650, if (on) p.ink else p.tx2))
                                    }
                                }
                            }
                            if (days.isEmpty()) Text(stringResource(R.string.alarm_once), style = mono(12f, 500, p.mu), modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    if (alarms.isNotEmpty()) Line()
                    Row(Modifier.fillMaxWidth().clickable {
                        val now = Calendar.getInstance()
                        TimePickerDialog(ctx, { _, hh, mm ->
                            save { c -> c.getJSONArray("alarms").put(JSONObject().put("time", "%02d:%02d".format(Locale.US, hh, mm)).put("on", true).put("days", JSONArray())) }
                        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(ctx)).show()
                    }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icn(Ic.plus, p.acct, 20.dp); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.alarm_add), style = sans(15f, 600, p.acct))
                    }
                }
            }
            item {
                Text(if (feat.wakeWa) stringResource(R.string.alarm_timing_whatsapp, cfg.optInt("recheck_min", 10), cfg.optInt("checkin_min", 15), cfg.optInt("escalate_min", 30))
                    else stringResource(R.string.alarm_timing, cfg.optInt("recheck_min", 10), cfg.optInt("checkin_min", 15)),
                    style = mono(11.5f, 500, p.mu), modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
            }
            item { Section(stringResource(R.string.alarm_section_reading_level), null) }
            item {
                val lv = ov?.optInt("level", 1) ?: 1
                val lmax = ov?.optInt("level_max", 8) ?: 8
                val auto = cfg.optBoolean("level_auto", true)
                val nextLv = ov?.optDouble("level_next", 0.0) ?: 0.0
                Panel {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.alarm_level_step, lv, lmax), style = sans(17f, 650, p.tx))
                            Text(stringResource(when { lv <= 3 -> R.string.alarm_level_beginner; lv <= 6 -> R.string.alarm_level_intermediate; else -> R.string.alarm_level_advanced }),
                                style = sans(13f, 400, p.mu, 18f))
                        }
                        IconBtn(Ic.back, p.tx, 40.dp, p.s2, enabled = lv > 1) { save { c -> c.put("level_base", lv - 1) } }
                        Spacer(Modifier.width(8.dp))
                        IconBtn(Ic.chevR, p.tx, 40.dp, p.s2, enabled = lv < lmax) { save { c -> c.put("level_base", lv + 1) } }
                    }
                    Line()
                    SettingRow(stringResource(R.string.alarm_level_auto), if (auto && nextLv > 0) stringResource(R.string.alarm_level_next, Fmt.weekdayDayMonth((nextLv * 1000).toLong()))
                        else if (auto) stringResource(R.string.alarm_level_top) else stringResource(R.string.alarm_level_fixed, lv)) {
                        Toggle(auto, { v -> save { c -> c.put("level_auto", v) } })
                    }
                }
            }
            if (feat.wakeWa) {
            item { Section(stringResource(R.string.alarm_section_escalation), if (wa.isBlank()) null else stringResource(R.string.alarm_whatsapp_status, bidiIsolate(wa)), if (wa == "connected") p.good else p.mu) }
            item {
                Panel {
                    contacts.forEachIndexed { i, c ->
                        if (i > 0) Line()
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.str("name").ifBlank { stringResource(R.string.alarm_contact_fallback) }, style = sans(15f, 600, p.tx).let { if (c.str("name").isBlank()) it else it.content() })
                                Text(c.str("phone"), style = mono(12f, 500, p.mu).ltr())
                            }
                            IconBtn(Ic.x, p.mu, 40.dp) { save { o -> o.getJSONArray("contacts").remove(i) } }
                        }
                    }
                    if (contacts.isNotEmpty()) Line()
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InputBox(addName, { addName = it }, stringResource(R.string.alarm_contact_name), Modifier.fillMaxWidth())
                        BasicPhone(addPhone, { addPhone = it }, stringResource(R.string.alarm_contact_phone))
                        Btn(stringResource(R.string.alarm_contact_add), Modifier.fillMaxWidth(), primary = false, enabled = addPhone.count(Char::isDigit) >= 9) {
                            save { o -> o.getJSONArray("contacts").put(JSONObject().put("name", addName.trim()).put("phone", addPhone.trim())) }
                            addName = ""; addPhone = ""
                        }
                    }
                }
            }
            }
            item { Section(stringResource(R.string.alarm_section_emergency_stop), null) }
            item {
                var oldPw by remember { mutableStateOf("") }
                var newPw by remember { mutableStateOf("") }
                Panel {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.alarm_password_note), style = sans(13f, 400, p.mu, 18f))
                        PwField(oldPw, { oldPw = it }, stringResource(R.string.alarm_password_current))
                        PwField(newPw, { newPw = it }, stringResource(R.string.alarm_password_new))
                        Btn(stringResource(R.string.alarm_password_change), Modifier.fillMaxWidth(), primary = false, enabled = oldPw.isNotEmpty() && newPw.length >= 8) {
                            scope.launch {
                                runCatching { Wake.request(ticket, "/v1/wake/password", "POST", JSONObject().put("old", oldPw).put("new", newPw)) }
                                    .onSuccess { Ui.say(if (it.optBoolean("ok")) str(R.string.alarm_password_changed) else it.str("reason")); if (it.optBoolean("ok")) { oldPw = ""; newPw = "" } }
                                    .onFailure { Ui.say(it.message) }
                            }
                        }
                    }
                }
            }
            if (!proofsGranted) item {
                Section(stringResource(R.string.alarm_section_proof_permissions), null)
                Panel {
                    SettingRow(stringResource(if (CAMERA_PERMISSIONS.isEmpty()) R.string.alarm_step_proof else R.string.alarm_proof_permissions),
                        stringResource(if (CAMERA_PERMISSIONS.isEmpty()) R.string.alarm_step_proof_sub else R.string.alarm_proof_permissions_sub)) {
                        Btn(stringResource(R.string.common_allow), primary = false) { askProofs.launch(proofPermissions.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()) }
                    }
                    Line()
                    SettingRow(stringResource(R.string.alarm_previously_denied), stringResource(R.string.alarm_previously_denied_sub)) {
                        Btn(stringResource(R.string.common_settings), primary = false) { settings(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) }
                    }
                }
            }
            item { Section(stringResource(R.string.alarm_section_testing), null) }
            item {
                Panel {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (feat.wakeWa) {
                            Text(stringResource(R.string.alarm_own_number_note), style = sans(13f, 400, p.mu, 18f))
                            BasicPhone(owner, { owner = it }, stringResource(R.string.alarm_own_number))
                            Btn(stringResource(R.string.alarm_save_number), Modifier.fillMaxWidth(), primary = false, enabled = owner.count(Char::isDigit) >= 9) { save { } }
                            Btn(stringResource(R.string.alarm_whatsapp_test), Modifier.fillMaxWidth(), primary = false) {
                                scope.launch { runCatching { Wake.request(ticket, "/v1/wake/whatsapp-test", "POST") }.onSuccess { Ui.say(if (it.optBoolean("ok")) str(R.string.alarm_whatsapp_sent) else it.str("error")) }.onFailure { Ui.say(it.message) } }
                            }
                        }
                        Btn(stringResource(R.string.alarm_test_now), Modifier.fillMaxWidth()) {
                            if (!Wake.canRing(ctx)) { Ui.say(R.string.alarm_test_needs_exact); return@Btn }
                            scope.launch {
                                runCatching { Wake.request(ticket, "/v1/wake/test", "POST") }.onSuccess { Wake.session.value = it.optJSONObject("session"); WakeService.start(ctx, "check") }.onFailure { Ui.say(it.message) }
                            }
                        }
                        Btn(stringResource(R.string.alarm_test_cancel), Modifier.fillMaxWidth(), danger = true) {
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
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), textStyle = mono(16f, 500, p.tx).ltr(),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(p.acct), modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(Modifier.clip(RoundedCornerShape(22.dp)).background(p.s2).border(1.dp, p.line, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (v.isEmpty()) Text(ph, style = mono(16f, 400, p.dim), modifier = Modifier.fillMaxWidth()); inner()
            }
        })
}

@Composable
private fun AnswerBox(v: String, on: (String) -> Unit, modifier: Modifier, onDone: () -> Unit) {
    val p = P
    androidx.compose.foundation.text.BasicTextField(v, on, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }),
        textStyle = sans(17f, 500, p.tx).content(), cursorBrush = androidx.compose.ui.graphics.SolidColor(p.acct),
        decorationBox = { inner ->
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(p.s2).border(1.dp, p.line, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (v.isEmpty()) Text(stringResource(R.string.wake_your_answer), style = sans(17f, 400, p.dim), modifier = Modifier.fillMaxWidth()); inner()
            }
        })
}

@Composable
private fun PwField(v: String, on: (String) -> Unit, ph: String) {
    val p = P
    androidx.compose.foundation.text.BasicTextField(v, on, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        textStyle = mono(16f, 500, p.tx).ltr(), cursorBrush = androidx.compose.ui.graphics.SolidColor(p.acct),
        decorationBox = { inner ->
            Box(Modifier.clip(RoundedCornerShape(22.dp)).background(p.s2).border(1.dp, p.line, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (v.isEmpty()) Text(ph, style = mono(16f, 400, p.dim), modifier = Modifier.fillMaxWidth()); inner()
            }
        })
}

/** Runs [block] each time the screen resumes: grants such as exact alarms change in Settings, outside the app. */
@Composable
fun OnResume(block: () -> Unit) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val latest by rememberUpdatedState(block)
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) latest()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/** Whether Wake can ring: exact alarm access, read again on every resume so a grant or a revoke shows at once. */
@Composable
fun rememberExactAlarmAccess(): Boolean {
    val ctx = LocalContext.current
    var revision by remember { mutableStateOf(0) }
    OnResume { revision++ }
    return remember(revision) { Wake.canExact(ctx) }
}
