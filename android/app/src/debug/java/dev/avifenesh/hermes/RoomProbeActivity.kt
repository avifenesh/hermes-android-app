package dev.avifenesh.hermes

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import org.json.JSONObject

/** Debug-only Group Chat fixtures on the production composables. No network: every callback only logs.
 *  Screens: list (Inbox, Rooms filter, one room waiting on an approval), all (Inbox, All filter), room,
 *  approval (a member's command in the log), members (the members sheet over the room), new (the New group
 *  chat sheet over the Inbox). Sheets use the app's own sheet, so their insets are the shipped ones. */
class RoomProbeActivity : FragmentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        check(!Prefs.paired && android.os.Build.PRODUCT.contains("sdk")) { "Disposable unpaired emulator only" }
        val theme = intent.getIntExtra("theme", 1)
        val screen = intent.getStringExtra("screen") ?: "room"
        val now = System.currentTimeMillis() / 1000.0
        val scout = RoomMember("scout", "scout", "scout", "Scout")
        val scribe = RoomMember("scribe", "scribe", "scribe", "Scribe")
        val critic = RoomMember("critic", "critic", "critic", "Critic")
        val crew = Room("crew", "Release crew", listOf(scout, scribe, critic), 42, 30, 3, now - 60, listOf("critic"),
            RoomPreview(41, "member", "Scribe", "Release notes are drafted; two numbers still cite a single run.", now - 70))
        val rooms = listOf(crew,
            Room("research", "Paper reading group", listOf(scout, critic), 17, 17, 0, now - 3600 * 5, emptyList(),
                RoomPreview(17, "user", "", "Let's pick this up after the benchmark lands.", now - 3600 * 5)),
            Room("ops", "Ops on-call with a very long group name that must ellipsize cleanly", listOf(scribe, scout), 8, 6, 1,
                now - 86400 * 2, emptyList(), RoomPreview(8, "member", "Scout", "Disk on the build box is at 91%.", now - 86400 * 2),
                // A member waiting on the owner: the row badges like a chat with a pending approval.
                listOf(RoomAction("approval", "scout", "dtask:2", 1, "req-2", "df -h && du -sh /var/cache/*", ""))))
        fun msg(seq: Long, who: RoomMember?, text: String, ago: Double, atts: List<JSONObject> = emptyList()) =
            RoomEvent(seq, "e$seq", "message", if (who == null) "user" else "member", who?.id.orEmpty(), who?.name.orEmpty(),
                text, now - ago, "t1", "", "", atts)
        fun notice(seq: Long, kind: String, who: RoomMember, detail: String, ago: Double) =
            RoomEvent(seq, "e$seq", "notice", "", who.id, who.name, "", now - ago, "t1", kind, detail, emptyList())
        val events = listOf(
            msg(20, null, "@scout check the eval numbers, then @scribe draft the release notes. Critic, keep them honest.", 3600 * 26.0),
            msg(21, scout, "Numbers check out on three runs: **p50 latency 41 ms**, p99 118 ms. One outlier run is excluded and noted.", 3600 * 26.0 - 40),
            msg(22, scout, "Raw table:\n\n| run | p50 | p99 |\n| --- | --- | --- |\n| a | 40 | 115 |\n| b | 42 | 121 |", 3600 * 26.0 - 50),
            notice(23, "turn.failed", critic, "provider timeout after 120 s", 3600 * 26.0 - 90),
            notice(24, "turn.cancelled", scribe, "superseded_by_newer_user_event", 3600 * 26.0 - 95),
            msg(30, null, "Here is the checklist from last time.", 600.0,
                listOf(JSONObject().put("attachment_id", "att_" + "1".repeat(32)).put("kind", "file").put("name", "release-checklist.md")
                    .put("mime", "text/markdown").put("size", 2048))),
            msg(31, scribe, "Drafted. I kept the claims to what the three runs show and linked the table.", 560.0),
            msg(41, scribe, "Release notes are drafted; two numbers still cite a single run.", 70.0),
            msg(42, critic, "Those two numbers need a second run before they ship. I'm checking the history now.", 30.0),
        )
        val approval = RoomAction("approval", "critic", "dtask:1", 2, "req-9", "git log --stat -n 20 -- benchmarks/", "Read the benchmark history")
        val driver = RoomDriver(true, true, false, mapOf("critic" to "running", "scout" to "queued"),
            if (screen == "approval") listOf(approval) else emptyList())
        val bots = listOf(RoomBot("default", "Hermes"), RoomBot("scout", "Scout"), RoomBot("scribe", "Scribe"),
            RoomBot("critic", "Critic"), RoomBot("researcher-with-a-long-profile-name", "Research Buddy"))
        if (screen == "list" || screen == "all" || screen == "new") {
            // The real Inbox reads the shared state flows; seed them with fixtures on this unpaired emulator.
            Hub.online.value = true
            Hub.chats.value = mapOf(
                "c1" to Chat("c1", "Plan the quarter", "chat", now - 300, 0, true, false, "Three options, ranked by cost.", "agent"),
                "c2" to Chat("c2", "Nightly digest", "digest", now - 7200, 2, false, false, "2 failures, 14 runs.", "agent"))
            Rooms.list.value = rooms.associateBy { it.id }
            Rooms.loaded.value = true
            Rooms.bots.value = bots
        }
        setContent {
            HermesTheme(theme) {
                CompositionLocalProvider(LocalAct provides this) {
                    Box(Modifier.fillMaxSize().background(P.bg)) {
                        when (screen) {
                            "list" -> InboxScreen(initialFilter = "rooms")
                            "all" -> InboxScreen(initialFilter = "all")
                            // Sheets render as the app shows them, over their screen, so their insets are the real ones.
                            "new" -> {
                                InboxScreen(initialFilter = "rooms")
                                RoomSheet({ Log.i("HermesRoomProbe", "DISMISS") }) {
                                    NewRoomContent(bots, busy = false, initialName = "Launch review", initialPicked = setOf("scout", "critic")) { name, picked ->
                                        Log.i("HermesRoomProbe", "CREATE " + JSONObject().put("name", name).put("members", org.json.JSONArray(picked)))
                                    }
                                }
                            }
                            else -> {
                                var members by remember { mutableStateOf(screen == "members") }
                                var draft by remember { mutableStateOf(intent.getStringExtra("draft") ?: "") }
                                val atts = remember { mutableStateListOf<JSONObject>() }
                                val list = remember { LazyListState(events.size + 5, 0) }
                                RoomBody(crew, events, driver, true, false, null, true, draft, { draft = it }, atts, false, null, list,
                                    RoomCallbacks(
                                        send = { Log.i("HermesRoomProbe", "SEND " + JSONObject().put("text", draft)); draft = "" },
                                        attachFile = { Log.i("HermesRoomProbe", "ATTACH") },
                                        older = { Log.i("HermesRoomProbe", "OLDER") },
                                        act = { a, choice -> Log.i("HermesRoomProbe", "ACT ${a.kind} ${a.member} $choice") },
                                        mention = { m -> draft = RoomLogic.mention(draft, m.handle) },
                                        members = { members = true },
                                        stop = { Log.i("HermesRoomProbe", "STOP") },
                                        rename = { Log.i("HermesRoomProbe", "RENAME") },
                                        delete = { Log.i("HermesRoomProbe", "DELETE") }))
                                if (members) RoomMembersSheet(crew, driver, onDismiss = { members = false }) { m ->
                                    members = false; draft = RoomLogic.mention(draft, m.handle)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
