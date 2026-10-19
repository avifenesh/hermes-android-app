package dev.avifenesh.hermes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Hands-free talk mode: listen (on-device SpeechRecognizer) -> send to the Talk chat -> speak the agent's reply (TextToSpeech)
 * -> listen again. Tap the orb to interrupt speech or stop listening. Runs while the screen is on (kept on here).
 */
class Talker(private val c: Context, private val onState: (String) -> Unit, private val onPartial: (String) -> Unit,
             private val onNotice: (String) -> Unit = {}, private val allowed: () -> Boolean = { true }) {
    var onHeard: (String) -> Unit = {}
    private var sr: SpeechRecognizer? = null
    private var srOnDevice = false
    private var cloudFallback = false   // set once on-device recognition failed with a language/service error
    private var tts: TextToSpeech? = null
    @Volatile var ttsReady = false
    var afterSpeak: (() -> Unit)? = null
    private val main = android.os.Handler(c.mainLooper)
    private var released = false
    private var listenGeneration = 0L
    private var speechGeneration = 0L
    private fun ui(f: () -> Unit) { main.post { if (!released) f() } }
    private fun speechCurrent(id: String?) = !released && allowed() && id?.substringBefore(":") == speechGeneration.toString()

    init {
        tts = TextToSpeech(c) { st ->
            val t = tts
            var ok = st == TextToSpeech.SUCCESS && t != null
            var lang = ""
            if (ok && t != null) {
                val want = Locale.getDefault()
                val r = t.setLanguage(want)
                lang = want.toLanguageTag()
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    val r2 = t.setLanguage(Locale.US); lang = "en-US (fallback from ${want.toLanguageTag()})"
                    if (r2 == TextToSpeech.LANG_MISSING_DATA || r2 == TextToSpeech.LANG_NOT_SUPPORTED) ok = false
                }
            }
            Log.i(TAG, "TTS init status=${if (st == TextToSpeech.SUCCESS) "SUCCESS" else "ERROR($st)"} ready=$ok lang=$lang")
            ui {
                ttsReady = ok
                if (!ok) onNotice(TTS_OFF)
            }
        }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { ui { if (speechCurrent(id)) onState("speaking") } }
                override fun onDone(id: String?) { ui { if (speechCurrent(id) && id?.endsWith(":last") == true) afterSpeak?.invoke() } }
                @Deprecated("old api") override fun onError(id: String?) { ui { if (speechCurrent(id)) afterSpeak?.invoke() } }
            })
        }
    }

    fun listen() {
        if (released || !allowed()) return
        stopListening()
        val generation = listenGeneration
        fun current() = !released && generation == listenGeneration && allowed()
        stopSpeaking()
        if (!SpeechRecognizer.isRecognitionAvailable(c)) {
            Log.w(TAG, "No speech recognizer available")
            onState("error:No speech recognizer on this phone"); return
        }
        val r = sr ?: run {
            srOnDevice = !cloudFallback && SpeechRecognizer.isOnDeviceRecognitionAvailable(c)
            Log.i(TAG, "creating ${if (srOnDevice) "on-device" else "default"} recognizer")
            (if (srOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(c) else SpeechRecognizer.createSpeechRecognizer(c)).also { sr = it }
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { if (!current()) return; Log.i(TAG, "recognizer started (onDevice=$srOnDevice)"); onState("listening") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { if (current()) onState("thinking") }
            override fun onError(e: Int) {
                if (!current()) return
                Log.w(TAG, "recognizer error $e (onDevice=$srOnDevice)")
                val langOrService = e == SpeechRecognizer.ERROR_SERVER_DISCONNECTED || e == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    e == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                when {
                    langOrService && srOnDevice && !cloudFallback -> {
                        // On-device pack missing for this language: retry once with the default (cloud) recognizer.
                        cloudFallback = true
                        runCatching { sr?.destroy() }; sr = null
                        main.post { if (current()) listen() }
                    }
                    e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onState("hint:Didn't catch that, tap to retry")
                    else -> onState("error:Recognizer error $e")
                }
            }
            override fun onResults(b: Bundle?) {
                if (!current()) return
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                if (t.isEmpty()) onState("hint:Didn't catch that, tap to retry") else onHeard(t)
            }
            override fun onPartialResults(b: Bundle?) {
                if (!current()) return
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag()))
    }

    fun stopListening() {
        listenGeneration++
        // A reused SpeechRecognizer can route an old binder result to its new listener.
        // Destroy the instance as well as fencing its callbacks before another capture.
        val old = sr; sr = null
        runCatching { old?.cancel() }; runCatching { old?.destroy() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var voiceJob: Job? = null
    private var player: MediaPlayer? = null

    /** Server voice first (Hermes TTS: tts.provider + voice, e.g. xAI "ara"); the phone's TTS engine only as fallback. */
    fun speak(text: String) {
        if (released || !allowed()) return
        val s = speakable(text).ifBlank { "Done." }
        stopSpeaking()
        val generation = speechGeneration
        if (!Prefs.serverVoice) { speakLocal(s); return }
        voiceJob = scope.launch {
            val files = try {
                val ids = Api.post("/v1/tts", JSONObject().put("text", s)).optJSONArray("ids") ?: JSONArray()
                (0 until ids.length()).map { i ->
                    if (released || generation != speechGeneration || !allowed()) throw CancellationException("Voice session changed")
                    val id = ids.getString(i)
                    File(c.cacheDir, "talk-$id").also { f -> Api.download("/v1/media/" + Uri.encode(id), f) {} }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "server voice failed: ${e.message}"); emptyList()
            }
            if (released || generation != speechGeneration || !allowed()) { files.forEach { it.delete() }; return@launch }
            if (files.isEmpty()) { onNotice(SERVER_VOICE_OFF); speakLocal(s) } else play(files, 0, generation)
        }
    }

    private fun play(files: List<File>, i: Int, generation: Long) {
        if (released || generation != speechGeneration || !allowed()) { files.forEach { it.delete() }; return }
        if (i >= files.size) { files.forEach { it.delete() }; player = null; afterSpeak?.invoke(); return }
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(files[i].path)
            mp.setOnCompletionListener { m -> m.release(); if (player === m) play(files, i + 1, generation) }
            mp.setOnErrorListener { m, w, x -> Log.w(TAG, "player error $w/$x"); m.release(); if (player === m) { player = null; if (generation == speechGeneration && allowed()) afterSpeak?.invoke() }; true }
            mp.prepare()
            if (i == 0) onState("speaking")
            mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "playback failed: ${e.message}"); mp.release(); player = null; files.forEach { it.delete() }; if (generation == speechGeneration && allowed()) afterSpeak?.invoke()
        }
    }

    private fun speakLocal(s: String) {
        if (released || !allowed()) return
        val t = tts
        if (t == null || !ttsReady) { onNotice(TTS_OFF); afterSpeak?.invoke(); return }
        val chunks = s.chunked(3500)
        chunks.forEachIndexed { i, ch -> t.speak(ch, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$speechGeneration:" + if (i == chunks.lastIndex) "last" else "p$i") }
    }

    fun stopSpeaking() {
        speechGeneration++
        voiceJob?.cancel(); voiceJob = null
        player?.let { p -> player = null; runCatching { p.stop() }; runCatching { p.release() } }
        runCatching { tts?.stop() }
    }

    fun release() { released = true; afterSpeak = null; stopListening(); stopSpeaking(); scope.cancel(); main.removeCallbacksAndMessages(null); runCatching { sr?.destroy() }; runCatching { tts?.shutdown() }; sr = null; tts = null }

    companion object {
        private const val TAG = "HermesTalk"
        const val TTS_OFF = "Voice output unavailable \u2014 replies shown as text"
        const val SERVER_VOICE_OFF = "Server voice unavailable \u2014 using the phone voice"
        /** Markdown -> something worth hearing: drop code blocks, tables, links' URLs. */
        /** Gateway show_reasoning prepends "💭 **Reasoning:**" + a fenced (or "> " quoted) block. Never speak it. */
        private val REASONING_FENCE = Regex("^\\s*💭[^\\n]*\\n```[\\s\\S]*?\\n```[ \\t]*(\\n|$)")
        private val REASONING_QUOTE = Regex("^\\s*💭[^\\n]*\\n(>[^\\n]*(\\n|$))+")
        fun stripReasoning(md: String): String = md.replace(REASONING_FENCE, "").replace(REASONING_QUOTE, "").trimStart()
        /** The reasoning text the gateway prepended, as plain prose, or null when there is none. */
        fun reasoningOf(md: String): String? {
            val m = REASONING_FENCE.find(md) ?: REASONING_QUOTE.find(md) ?: return null
            return m.value.lineSequence().drop(1).map { it.removePrefix(">").trim() }.filter { it.isNotBlank() && !it.startsWith("```") }
                .joinToString("\n").ifBlank { null }
        }
        fun speakable(md: String): String = stripReasoning(md)
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .lines().filterNot { it.trimStart().startsWith("|") }.joinToString("\n")
            .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
            .replace(Regex("https?://\\S+"), "a link")
            .replace(Regex("[*_`#>]"), "")
            .replace(Regex("\\n{2,}"), ". ").trim()
    }
}

@Composable
fun TalkScreen() {
    val p = P
    val ctx = LocalContext.current
    val act = LocalAct.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf("idle") }
    var heard by remember { mutableStateOf("") }
    var said by remember { mutableStateOf("") }
    var cid by remember { mutableStateOf(Prefs.talkChat) }
    var handsFree by remember { mutableStateOf(true) }
    var waitingSince by remember { mutableStateOf(0.0) }
    var notice by remember { mutableStateOf("") }
    var ticket by remember { mutableStateOf<VoiceTicket?>(null) }
    var permissionTicket by remember { mutableStateOf<VoiceTicket?>(null) }
    var startJob by remember { mutableStateOf<Job?>(null) }
    var sendJob by remember { mutableStateOf<Job?>(null) }
    fun valid(t: VoiceTicket?): Boolean = VoiceSession.valid(t) && Prefs.paired &&
        Ui.stack.lastOrNull() == Route.TalkR && !Ui.composeOpen.value && Ui.pairLink.value == null &&
        (t?.wake != true || (VoiceLaunch.unlocked(ctx) && act.hasWindowFocus() &&
            act.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)))
    val talker = remember {
        Talker(ctx, onState = { state = it }, onPartial = { heard = it }, onNotice = { notice = it },
            allowed = { valid(ticket) })
    }
    fun cancelWork() {
        ticket = null // Keep the original permission ticket until its one callback returns.
        startJob?.cancel(); startJob = null; sendJob?.cancel(); sendJob = null
        talker.afterSpeak = null; talker.stopListening(); talker.stopSpeaking()
        state = "idle"; WakeListen.talk(false)
    }
    val all by Hub.msgs.collectAsState()
    val work by Hub.working.collectAsState()
    DisposableEffect(Unit) {
        // Screen may go off: the mic foreground service + showWhenLocked keep the loop going under the lock screen.
        act.setShowWhenLocked(true)
        act.setTurnScreenOn(true)
        val removeStop = VoiceSession.observe { cancelWork() }
        onDispose {
            removeStop()
            val owned = ticket
            cancelWork()
            if (owned != null && VoiceSession.gate.current == owned) VoiceSession.revoke()
            act.setShowWhenLocked(false); act.setTurnScreenOn(false); talker.release(); TalkService.stop(ctx); WakeListen.talk(false)
        }
    }
    LaunchedEffect(Unit) { TalkBus.ended.collect { VoiceSession.revoke(); handsFree = false } }
    // Machine reset invalidates checks immediately; this observer also stops an idle recognizer promptly.
    LaunchedEffect(Unit) { Wake.generation.collect { if (ticket != null && !VoiceSession.valid(ticket)) VoiceSession.revoke() } }
    LaunchedEffect(state) {
        // Also gate this queued effect: a revoked render must not re-pause the detector or restart the service.
        val active = valid(ticket) && state in setOf("listening", "speaking", "waiting", "sending", "thinking")
        WakeListen.talk(active)
        if (active) TalkService.start(ctx, when (state) { "listening" -> "Listening"; "speaking" -> "Speaking"; else -> "Thinking" })
        else TalkService.stop(ctx)
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val requested = permissionTicket
        permissionTicket = null
        if (requested != null && requested == ticket && valid(requested) && VoiceLaunch.unlocked(ctx)) {
            if (ok) talker.listen() else state = "error:Microphone permission denied"
        }
    }
    fun continueListening(t: VoiceTicket?) {
        if (t == null || t != ticket || !valid(t)) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            state = "thinking"
            talker.listen()
        } else {
            if (permissionTicket != null) return // Never attach an old permission result to a new session.
            permissionTicket = t
            mic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    fun start() {
        // Only a fresh, unlocked UI tap mints manual authority; callbacks never call this.
        if (!VoiceLaunch.unlocked(ctx) || !act.hasWindowFocus()) return
        ticket = VoiceSession.begin(wake = false)
        continueListening(ticket)
    }
    val speakingTicket = ticket
    talker.afterSpeak = {
        if (speakingTicket != null && speakingTicket == ticket && valid(speakingTicket)) {
            state = "idle"
            if (handsFree) continueListening(speakingTicket)
        }
    }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { Ui.talkListen.value }.collect { requested ->
            if (requested) {
                Ui.talkListen.value = false
                val queued = VoiceSession.gate.current
                if (queued?.wake == true && valid(queued)) {
                    ticket = queued
                    WakeListen.talk(true)
                    startJob?.cancel()
                    startJob = scope.launch {
                        kotlinx.coroutines.delay(300) // Existing detector-to-recognizer mic handoff, not an authorization timeout.
                        continueListening(queued)
                    }
                }
            }
        }
    }
    val onFinal: (String) -> Unit = { text ->
        val origin = ticket
        if (origin != null && valid(origin)) {
            sendJob = scope.launch {
                state = "sending"
                try {
                    if (!valid(origin)) return@launch
                    val id = cid ?: Hub.newChat("Talk")
                    if (!valid(origin)) return@launch
                    cid = id; Prefs.talkChat = id
                    waitingSince = nowS()
                    Hub.send(id, text)
                    if (valid(origin)) state = "waiting"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (valid(origin)) state = "error:" + (e.message ?: "send failed") }
            }
        }
    }
    talker.onHeard = { t -> if (valid(ticket)) { heard = t; onFinal(t) } }
    // reply arrived -> speak
    val reply = cid?.let { c -> all[c].orEmpty().lastOrNull { it.role != "user" && it.kind == "text" && it.ts >= waitingSince - 1 && it.content.isNotBlank() } }
    LaunchedEffect(reply?.id, work.containsKey(cid)) {
        if (!valid(ticket) || state != "waiting" || reply == null) return@LaunchedEffect
        if (cid != null && work.containsKey(cid)) return@LaunchedEffect   // wait for the turn to finish
        said = Talker.stripReasoning(reply.content)
        talker.speak(reply.content)
    }
    val running = cid != null && work.containsKey(cid)
    Column(Modifier.fillMaxSize().background(p.bg)) {
        TopBar("Talk", if (cid != null) "voice · " + (Hub.chats.collectAsState().value[cid]?.title ?: "Talk") else "voice") {
            Text(if (handsFree) "Hands-free" else "Push to talk", style = sans(13f, 600, if (handsFree) p.acct else p.mu),
                modifier = Modifier.padding(end = 10.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(p.s2)
                    .clickable { handsFree = !handsFree }.padding(horizontal = 12.dp, vertical = 7.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (heard.isNotBlank()) {
                Text("YOU", style = mono(11f, 700, p.mu)); Text(heard, style = sans(20f, 500, p.tx, 28f))
            }
            if (said.isNotBlank()) {
                Text(agentName().uppercase(), style = mono(11f, 700, p.acct)); Md(said, size = 17f)
            }
            if (heard.isBlank() && said.isBlank()) Text("Tap the orb and talk. ${agentName()} answers out loud, then listens again.", style = sans(16f, 400, p.mu, 24f))
        }
        val label = when {
            state.startsWith("error:") -> state.removePrefix("error:")
            state.startsWith("hint:") -> state.removePrefix("hint:")
            state == "listening" -> "Listening…"
            state == "thinking" || state == "sending" -> "Got it…"
            state == "waiting" -> if (running) "${agentName()} is working…" else "Waiting for the reply…"
            state == "speaking" -> "Speaking · tap to interrupt"
            else -> "Tap to talk"
        }
        if (notice.isNotBlank()) Text(notice, style = sans(13f, 500, p.mu), textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp))
        Text(label, style = sans(15f, 600, if (state.startsWith("error")) p.bad else p.tx2), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text("Talk may send audio to your system speech provider. Transcripts go to your paired gateway; spoken replies may use its voice provider or your phone's TTS service.",
            style = sans(12.5f, 400, p.tx2, 18f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp))
        Spacer(Modifier.height(18.dp))
        val pulse by rememberInfiniteTransition(label = "orb").animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "s")
        val active = state in setOf("listening", "speaking", "waiting", "sending", "thinking")
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 36.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(120.dp).scale(if (active) pulse else 1f).clip(CircleShape)
                .background(when (state) { "listening", "speaking" -> p.acc; else -> p.s3 })
                .semantics { contentDescription = label }
                .clickable {
                    when (state) {
                        "speaking" -> { talker.stopSpeaking(); state = "idle"; start() }
                        "listening" -> VoiceSession.revoke()
                        "waiting" -> { if (running && cid != null) scope.launch { runCatching { Hub.stop(cid!!) } }; VoiceSession.revoke() }
                        else -> start()
                    }
                }, contentAlignment = Alignment.Center) {
                Icn(if (state == "speaking" || state == "waiting") Ic.stop else Ic.mic, if (state == "listening" || state == "speaking") p.ink else p.tx, 44.dp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.Center) {
            Text("New conversation", style = sans(13.5f, 600, p.mu), modifier = Modifier.clickable { VoiceSession.revoke(); cid = null; Prefs.talkChat = null; heard = ""; said = "" }.padding(8.dp))
            if (cid != null) Text("Open as chat", style = sans(13.5f, 600, p.mu), modifier = Modifier.clickable { Ui.open(cid!!) }.padding(8.dp))
        }
    }
}
