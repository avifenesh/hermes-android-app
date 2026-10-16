package dev.avifenesh.hermes

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity

/** Debug-only entry for reproducible screen coverage against an isolated, paired test server. */
class UiAuditActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs.paired) { finish(); return }
        Ui.paired.value = true
        Ui.theme.intValue = intent.getIntExtra("theme", Prefs.theme)
        Ui.accent.intValue = Prefs.accent
        Ui.stack.clear()
        Ui.composeOpen.value = false
        Ui.tab.value = "inbox"
        val id = intent.getStringExtra("id") ?: "c-ui-0"
        val route = when (intent.getStringExtra("screen")) {
            "inbox" -> null
            "pulse" -> { Ui.tab.value = "pulse"; null }
            "cron" -> { Ui.tab.value = "cron"; null }
            "me" -> { Ui.tab.value = "me"; null }
            "compose" -> { Ui.composeOpen.value = true; null }
            "chat" -> Route.ChatR(id)
            "run" -> Route.RunR(id)
            "activity" -> Route.ActR(id, "c-ui-0")
            "job" -> Route.JobR(id)
            "session" -> Route.SessR(id, "Test session", "fixture")
            "elsewhere" -> Route.Elsewhere
            "wake" -> Route.WakeR
            "settings" -> Route.SettingsR
            "phone" -> Route.PhoneR
            "all-settings" -> Route.AllSettingsR
            "extensions" -> Route.ExtR
            "memory" -> Route.MemoryR
            "skill" -> Route.SkillR(id)
            "cron-edit" -> Route.CronEditR(null)
            "search" -> Route.SearchR
            "usage" -> Route.UsageR
            "agent" -> Route.AgentR(id, "Test agent")
            "talk" -> Route.TalkR
            "projects" -> Route.ProjectsR
            "repo" -> Route.RepoR(id)
            "hub" -> Route.HubR
            "hub-skill" -> Route.HubSkillR(id)
            "hub-plugin" -> Route.HubPluginR(id)
            "hub-mcp" -> Route.HubMcpR(id)
            "radar" -> Route.RadarR
            else -> null
        }
        route?.let(Ui::push)
        setContent { HermesTheme(Ui.theme.intValue) { Root(this) } }
    }
}
