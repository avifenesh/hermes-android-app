package dev.avifenesh.hermes

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat

object Notify {
    const val CH_APPR = "approvals"
    const val CH_MSG = "replies"
    const val CH_DIG = "digests"
    const val CH_LIVE = "live"
    private const val LIME = 0xFFD4F75C.toInt()
    private const val ORANGE = 0xFFFF8A66.toInt()

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_APPR, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Commands waiting for your OK"; enableVibration(true); lockscreenVisibility = Notification.VISIBILITY_PRIVATE },
            NotificationChannel(CH_MSG, "Replies", NotificationManager.IMPORTANCE_HIGH).apply { description = "Agent replies and finished runs" },
            NotificationChannel(CH_DIG, "Cron & digests", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Results from scheduled jobs" },
            NotificationChannel(CH_LIVE, "Live runs & connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Run progress and the background connection"; setShowBadge(false) },
        ))
    }

    fun can(c: Context) =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(c).areNotificationsEnabled()
    private fun nid(s: String) = s.hashCode()
    private const val PI = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun openIntent(c: Context, cid: String): PendingIntent = PendingIntent.getActivity(c, nid("open$cid"),
        Intent(c, MainActivity::class.java).putExtra("chat", cid).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PI)

    fun post(c: Context, id: Int, n: Notification) {
        if (!can(c)) return
        try {
            NotificationManagerCompat.from(c).notify(id, n)
        } catch (_: SecurityException) {
            // Permission can be revoked between the availability check and posting.
        }
    }

    fun consider(c: Context, m: Msg) {
        if (m.seq <= Prefs.nseq) return
        Prefs.nseq = m.seq
        if (m.role == "user" || m.kind in setOf("trace", "activity", "status", "deleted", "approval", "steer", "queued")) return
        if (Hub.foreground) return
        message(c, m)
    }

    private fun message(c: Context, m: Msg) {
        val chat = Hub.chats.value[m.chatId]
        val digest = chat?.kind == "digest" || m.chatId.startsWith("cron-")
        val zl = Person.Builder().setName(Hub.agent.value).setKey("agent").build()
        val me = Person.Builder().setName("You").setKey("me").build()
        val n = (chat?.unread ?: 1).coerceIn(1, 5)
        val recent = Hub.msgs.value[m.chatId].orEmpty().filter { it.role != "user" && it.kind !in setOf("trace", "activity", "status", "deleted", "approval") }.takeLast(n)
            .ifEmpty { listOf(m) }
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(chat?.title ?: "Hermes").setGroupConversation(false)
        recent.forEach { style.addMessage(Hub.plain(if (it.kind == "text") it.content else Hub.previewOf(it)).take(900), (it.ts * 1000).toLong(), zl) }
        val reply = RemoteInput.Builder("reply").setLabel("Reply to " + Hub.agent.value).build()
        val replyPi = PendingIntent.getBroadcast(c, nid("r${m.chatId}"),
            Intent(c, ActionReceiver::class.java).setAction("reply").putExtra("chat", m.chatId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val readPi = PendingIntent.getBroadcast(c, nid("rd${m.chatId}"),
            Intent(c, ActionReceiver::class.java).setAction("read").putExtra("chat", m.chatId), PI)
        val b = NotificationCompat.Builder(c, if (digest) CH_DIG else CH_MSG)
            .setSmallIcon(R.drawable.ic_stat).setColor(LIME).setStyle(style)
            .setContentIntent(openIntent(c, m.chatId)).setAutoCancel(true).setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addAction(NotificationCompat.Action.Builder(0, "Reply", replyPi).addRemoteInput(reply)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).setShowsUserInterface(false).build())
            .addAction(NotificationCompat.Action.Builder(0, "Mark read", readPi)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).setShowsUserInterface(false).build())
        post(c, nid("c${m.chatId}"), b.build())
    }

    fun approval(c: Context, a: Appr) {
        if (a.state != "pending") { cancelApproval(c, a.id); return }
        if (Hub.foreground && Hub.openChat == a.chatId) return
        val chat = Hub.chats.value[a.chatId]
        val approve = PendingIntent.getActivity(c, nid("ap${a.id}"),
            Intent(c, ApproveActivity::class.java).putExtra("aid", a.id).putExtra("choice", "once").putExtra("cmd", a.command)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY), PI)
        val sess = PendingIntent.getActivity(c, nid("as${a.id}"),
            Intent(c, ApproveActivity::class.java).putExtra("aid", a.id).putExtra("choice", "session").putExtra("cmd", a.command)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY), PI)
        val deny = PendingIntent.getBroadcast(c, nid("dn${a.id}"),
            Intent(c, ActionReceiver::class.java).setAction("deny").putExtra("aid", a.id), PI)
        val ctxMeta = Hub.msgs.value[a.chatId].orEmpty().lastOrNull { it.kind == "approval" && it.meta.str("approval_id") == a.id }?.meta
        val files = ctxMeta?.optJSONArray("files").strs()
        val body = listOf(a.description, "$ " + a.command,
            if (files.isNotEmpty()) "Touched: " + files.take(4).joinToString(", ") { it.substringAfterLast('/') } + if (files.size > 4) " +${files.size - 4}" else "" else "",
            if (ctxMeta?.optBoolean("smart_denied") == true) "⚠ flagged by Hermes' safety check" else "")
            .filter { it.isNotBlank() && it != "$ " }.joinToString("\n")
        val n = NotificationCompat.Builder(c, CH_APPR)
            .setSmallIcon(R.drawable.ic_stat).setColor(ORANGE)
            .setContentTitle("Approve? · " + (chat?.title ?: "Hermes")).setContentText(a.command)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(true)
            .setContentIntent(openIntent(c, a.chatId))
            .addAction(0, "Approve", approve).addAction(0, "This session", sess).addAction(0, "Deny", deny)
            .setPublicVersion(NotificationCompat.Builder(c, CH_APPR).setSmallIcon(R.drawable.ic_stat).setColor(ORANGE)
                .setContentTitle("Approval needed").setContentText(chat?.title ?: "Hermes").build())
            .build()
        post(c, nid("a${a.id}"), n)
    }

    fun cancelApproval(c: Context, aid: String) = NotificationManagerCompat.from(c).cancel(nid("a$aid"))
    fun cancelChat(c: Context, cid: String) = NotificationManagerCompat.from(c).cancel(nid("c$cid"))

    fun live(c: Context, working: Map<String, Double>): Notification {
        val b = NotificationCompat.Builder(c, CH_LIVE).setSmallIcon(R.drawable.ic_stat).setColor(LIME)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
            .setContentIntent(PendingIntent.getActivity(c, 3, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PI))
        if (working.isEmpty()) {
            b.setContentTitle("Hermes").setContentText(if (Hub.online.value) "Connected" else "Reconnecting…")
        } else {
            val (cid, since) = working.entries.maxBy { it.value }
            b.setContentTitle(Hub.agent.value + " is working").setContentText(Hub.chats.value[cid]?.title ?: "A run")
                .setProgress(0, 0, true).setShowWhen(true).setUsesChronometer(true).setWhen((since * 1000).toLong())
                .setContentIntent(openIntent(c, cid))
            if (working.size > 1) b.setSubText("${working.size} runs")
        }
        return b.build()
    }
}
