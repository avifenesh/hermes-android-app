package dev.avifenesh.hermes

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Debug-only voice bubble and agent orb fixture. No network: the clips are seeded into the media
 * cache under the exact names [fetchMedia] reads, so the bubbles run the production fetch-cache,
 * decoder and player paths on the bundled clip.
 *
 * assets/probe/voice-fixture.ogg is synthetic, not a recording: 3.2 s of harmonic tones under
 * eight syllable-shaped envelopes, 48 kHz mono, Opus 24 kb/s in Ogg (the recorder's format).
 *
 * am start -n <package>/dev.avifenesh.hermes.VoiceProbeActivity --ei theme 1|2 (1 Night, 2 Day)
 */
class VoiceProbeActivity : FragmentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        check(!Prefs.paired && android.os.Build.PRODUCT.contains("sdk")) { "Disposable unpaired emulator only" }
        val theme = intent.getIntExtra("theme", 1)
        val clip = assets.open("probe/voice-fixture.ogg").use { it.readBytes() }
        fun seed(id: String, name: String, bytes: ByteArray): JSONObject {
            mediaCacheFile(this, id, name).writeBytes(bytes)
            return JSONObject().put("id", id).put("kind", "voice").put("mime", "audio/ogg").put("file_name", name).put("size", bytes.size)
        }
        val sent = seed("probe-sent.ogg", "note.ogg", clip)
            .put("transcript", "Turn the porch lights off, and remind me about the parcel at six.")
        val reply = seed("probe-reply.ogg", "reply.ogg", clip)
        val plain = seed("probe-plain.ogg", "plain.ogg", clip)
        val broken = seed("probe-broken.ogg", "broken.ogg", "not an audio file".toByteArray())
        val now = System.currentTimeMillis() / 1000.0
        val chat = chatVisible(listOf(
            Msg("p1", "probe", "user", "text", "", JSONObject().put("attachments", JSONArray().put(sent)), now - 300, 1),
            // Hermes' echo of the transcript: hidden because its text sits on p1.
            Msg("p1e", "probe", "agent", "status", "\uD83C\uDF99\uFE0F \"Turn the porch lights off\"",
                JSONObject().put("quiet", true).put("transcript_of", "p1"), now - 299, 2),
            Msg("p2", "probe", "user", "text", "And add milk to the list.", JSONObject(), now - 290, 3),
            Msg("p3", "probe", "agent", "text", "Done. The porch lights are off, I will remind you about the parcel at **18:00**, and milk is on the list.",
                JSONObject(), now - 280, 4),
            Msg("p4", "probe", "agent", "voice", "Here it is as audio too.", JSONObject(reply.toString()).put("media", reply.str("id")), now - 275, 5),
        ))
        val long = FloatArray(Waves.BUCKETS) { i -> if (i % 17 in 7..8) 0.06f else (0.2f + 0.8f * abs(sin(i * 0.61f) * cos(i * 0.17f))) }
        val transcript = "Okay so the plan for tomorrow: pick up the parcel before six, then call the garage about the brakes. " +
            "If they can't do Thursday, ask for Monday morning and tell them the car is a 2019 hatchback."
        setContent {
            HermesTheme(theme) {
                VoicePlaybackScope()
                LazyColumn(Modifier.fillMaxSize().background(P.bg).statusBarsPadding().navigationBarsPadding(),
                    contentPadding = PaddingValues(bottom = 32.dp)) {
                    item { Text("Voice bubbles", style = serif(32f, P.tx), modifier = Modifier.padding(start = 20.dp, top = 16.dp)) }
                    item { Text("Fixture only. Tap to play the bundled clip; nothing leaves the phone.", style = sans(13f, 400, P.mu),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
                    item { Section("In a chat") }
                    items(chat.size) { i ->
                        MsgView(chat[i], emptyMap(), compact = false, footer = endsRun(chat, i, ::quietInRun),
                            lead = leadsRun(chat, i, ::quietInRun) { _, _ -> true })
                    }
                    item { WorkingRow(now - 42) {} }
                    item { Section("States") }
                    item { Label("Received, long, playing at 38%, 1.5x") }
                    item {
                        Agent { VoiceBubbleContent(mine = false, peaks = long, ready = true, unavailable = false, durationMs = 252_000,
                            positionMs = { 95_760 }, current = true, playing = true, speed = 1.5f, transcript = "",
                            onToggle = {}, onSeek = {}, onSpeed = {}) }
                    }
                    item { Label("Sent, long, paused at 62%, transcript open") }
                    item {
                        Mine { VoiceBubbleContent(mine = true, peaks = long, ready = true, unavailable = false, durationMs = 252_000,
                            positionMs = { 156_240 }, current = true, playing = false, speed = 1f, transcript = transcript,
                            onToggle = {}, onSeek = {}, onSpeed = {}, transcriptOpen = true) }
                    }
                    item { Label("Sent, short, idle (live: tap to play)") }
                    item { Mine { VoiceBubble(plain, mine = true) } }
                    item { Label("Waveform still computing") }
                    item {
                        Agent { VoiceBubbleContent(mine = false, peaks = null, ready = false, unavailable = false, durationMs = 0,
                            positionMs = { 0 }, current = false, playing = false, speed = 1f, transcript = "",
                            onToggle = {}, onSeek = {}, onSpeed = {}) }
                    }
                    item { Label("Decode failure (real decoder on a broken file)") }
                    item { Agent { VoiceBubble(broken, mine = false) } }
                    item { Label("Download failed") }
                    item {
                        Agent { VoiceBubbleContent(mine = false, peaks = null, ready = false, unavailable = true, durationMs = 0,
                            positionMs = { 0 }, current = false, playing = false, speed = 1f, transcript = "",
                            onToggle = {}, onSeek = {}, onSpeed = {}) }
                    }
                    item { Section("Agent orb") }
                    item {
                        FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp),
                            itemVerticalAlignment = Alignment.CenterVertically) {
                            OrbSample("idle") { AgentOrb(AVATAR) }
                            OrbSample("working") { AgentOrb(AVATAR, live = true) }
                            OrbSample("motion off") { AgentOrb(AVATAR, live = true, still = true) }
                            OrbSample("40 dp") { AgentOrb(40.dp, live = true) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = mono(11f, 500, P.dim), modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp))

/** The agent-side frame a chat gives a voice bubble: avatar column, then the bubble. */
@Composable
private fun Agent(content: @Composable () -> Unit) = Row(Modifier.fillMaxWidth().padding(start = AVATAR_START, end = 14.dp)) {
    Spacer(Modifier.width(AVATAR + AVATAR_GAP + 6.dp))
    Box(Modifier.weight(1f)) { content() }
}

/** The user-side frame: the sent bubble's shape and fill, right-aligned. */
@Composable
private fun Mine(content: @Composable () -> Unit) = Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalAlignment = Alignment.End) {
    Box(Modifier.widthIn(max = 310.dp).clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)).background(P.s3)
        .padding(horizontal = 15.dp, vertical = 10.dp)) { content() }
}

@Composable
private fun OrbSample(label: String, orb: @Composable () -> Unit) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.padding(12.dp), contentAlignment = Alignment.Center) { orb() }
    Text(label, style = mono(10.5f, 500, P.mu))
}
