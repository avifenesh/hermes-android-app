package dev.avifenesh.hermes

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/** Home-screen widget: waiting / working / approvals counts, the top waiting agent, and Talk / New / Fleet buttons. */
class FleetWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        Widget.update(c)
        if (Prefs.paired) WorkManager.getInstance(c).enqueueUniqueWork("widget", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<SyncWorker>().build())
    }
}

object Widget {
    private fun go(c: Context, what: String, code: Int): PendingIntent = PendingIntent.getActivity(c, code,
        Intent(c, MainActivity::class.java).setAction("dev.avifenesh.hermes.$what").putExtra("go", what)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun update(c: Context?) {
        c ?: return
        runCatching {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, FleetWidget::class.java))
            if (ids.isEmpty()) return
            val rv = RemoteViews(c.packageName, R.layout.widget_fleet)
            // Static widget can't be hidden at runtime; with Fleet off show no stale counts (tap still lands on Pulse).
            val fleetOn = Hub.features.value.fleet
            val snap = if (fleetOn) Fleet.snap.value else null
            val agents = if (fleetOn) Fleet.agents() else emptyList()
            val appr = Hub.approvals.value.values.count { it.state == "pending" }
            // Unpaired, the widget names the app; paired, it names the user's agent.
            rv.setTextViewText(R.id.w_name, if (Prefs.paired) Prefs.agentName else APP_NAME)
            rv.setTextViewText(R.id.w_wait, if (snap == null) "–" else Fmt.int(Fleet.count("waiting")))
            rv.setTextViewText(R.id.w_work, if (snap == null) "–" else Fmt.int(Fleet.count("working")))
            rv.setTextViewText(R.id.w_appr, Fmt.int(appr))
            // The launcher inflates the layout in the system language; set the labels in the app language.
            rv.setTextViewText(R.id.w_wait_label, str(R.string.widget_waiting))
            rv.setTextViewText(R.id.w_work_label, str(R.string.widget_working))
            rv.setTextViewText(R.id.w_appr_label, str(R.string.widget_approvals))
            rv.setTextViewText(R.id.w_talk, str(R.string.widget_talk))
            rv.setTextViewText(R.id.w_new, str(R.string.widget_new))
            rv.setTextViewText(R.id.w_fleet, str(R.string.widget_fleet))
            val top = agents.firstOrNull { it.str("group") == "waiting" }
            rv.setTextViewText(R.id.w_top, when {
                !Prefs.paired -> str(R.string.widget_open_to_pair, bidiIsolate(APP_NAME))
                top != null -> top.str("name").ifBlank { top.str("kind") } + ": " + top.str("detail")
                agents.any { it.str("group") == "working" } -> agents.first { it.str("group") == "working" }.let { it.str("name") + ": " + it.str("detail") }
                else -> str(R.string.widget_all_quiet)
            })
            rv.setTextViewText(R.id.w_when, if (snap == null) "" else clock(snap.optDouble("at", nowS())))
            rv.setOnClickPendingIntent(R.id.w_root, go(c, "fleet", 41))
            rv.setOnClickPendingIntent(R.id.w_fleet, go(c, "fleet", 42))
            rv.setOnClickPendingIntent(R.id.w_talk, go(c, "talk", 43))
            rv.setOnClickPendingIntent(R.id.w_new, go(c, "compose", 44))
            m.updateAppWidget(ids, rv)
        }
    }
}

/** Quick Settings tile: one swipe down, tap, talk. Subtitle shows how many agents wait on you. */
class TalkTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = str(R.string.tile_talk_to, bidiIsolate(Prefs.agentName))
            val w = if (Hub.features.value.fleet) Fleet.count("waiting") else 0
            subtitle = if (w > 0) plural(R.plurals.tile_waiting, w, w) else str(R.string.tile_hands_free)
            updateTile()
        }
    }

    // The PendingIntent overload is API 34+; API 31-33 require the legacy Intent overload.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val i = Intent(this, MainActivity::class.java).setAction("dev.avifenesh.hermes.talk").putExtra("go", "talk")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 45, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }
}
