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
            NotificationChannel(CH_APPR, str(R.string.notify_channel_approvals), NotificationManager.IMPORTANCE_HIGH).apply {
                description = str(R.string.notify_channel_approvals_info); enableVibration(true); lockscreenVisibility = Notification.VISIBILITY_PRIVATE },
            NotificationChannel(CH_MSG, str(R.string.notify_channel_replies), NotificationManager.IMPORTANCE_HIGH).apply { description = str(R.string.notify_channel_replies_info) },
            NotificationChannel(CH_DIG, str(R.string.notify_channel_digests), NotificationManager.IMPORTANCE_DEFAULT).apply { description = str(R.string.notify_channel_digests_info) },
            NotificationChannel(CH_LIVE, str(LiveService.CHANNEL_NAME), NotificationManager.IMPORTANCE_LOW).apply {
                description = str(LiveService.CHANNEL_DESCRIPTION); setShowBadge(false) },
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
        val me = Person.Builder().setName(str(R.string.common_you)).setKey("me").build()
        val n = (chat?.unread ?: 1).coerceIn(1, 5)
        val recent = Hub.msgs.value[m.chatId].orEmpty().filter { it.role != "user" && it.kind !in setOf("trace", "activity", "status", "deleted", "approval") }.takeLast(n)
            .ifEmpty { listOf(m) }
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(chat?.title ?: HERMES).setGroupConversation(false)
        recent.forEach { style.addMessage(Hub.plain(if (it.kind == "text") it.content else Hub.previewOf(it)).take(900), (it.ts * 1000).toLong(), zl) }
        val reply = RemoteInput.Builder("reply").setLabel(str(R.string.notify_reply_to, bidiIsolate(Hub.agent.value))).build()
        val replyPi = PendingIntent.getBroadcast(c, nid("r${m.chatId}"),
            Intent(c, ActionReceiver::class.java).setAction("reply").putExtra("chat", m.chatId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val readPi = PendingIntent.getBroadcast(c, nid("rd${m.chatId}"),
            Intent(c, ActionReceiver::class.java).setAction("read").putExtra("chat", m.chatId), PI)
        val b = NotificationCompat.Builder(c, if (digest) CH_DIG else CH_MSG)
            .setSmallIcon(R.drawable.ic_stat).setColor(LIME).setStyle(style)
            .setContentIntent(openIntent(c, m.chatId)).setAutoCancel(true).setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // A read-only mirror (group chat elsewhere) takes no replies from the phone; the server refuses them.
            .apply { if (chat?.readonly.isNullOrBlank()) addAction(NotificationCompat.Action.Builder(0, str(R.string.notify_reply), replyPi).addRemoteInput(reply)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).setShowsUserInterface(false).build()) }
            .addAction(NotificationCompat.Action.Builder(0, str(R.string.notify_mark_read), readPi)
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
            if (files.isNotEmpty()) str(R.string.notify_touched, bidiIsolate(files.take(4).joinToString(", ") { it.substringAfterLast('/') }) + if (files.size > 4) " +${files.size - 4}" else "") else "",
            if (ctxMeta?.optBoolean("smart_denied") == true) str(R.string.notify_flagged, bidiIsolate(HERMES)) else "")
            .filter { it.isNotBlank() && it != "$ " }.joinToString("\n")
        val n = NotificationCompat.Builder(c, CH_APPR)
            .setSmallIcon(R.drawable.ic_stat).setColor(ORANGE)
            .setContentTitle(str(R.string.notify_approve_title, bidiIsolate(chat?.title ?: HERMES))).setContentText(a.command)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(true)
            .setContentIntent(openIntent(c, a.chatId))
            .addAction(0, str(R.string.common_approve), approve).addAction(0, str(R.string.notify_this_session), sess).addAction(0, str(R.string.common_deny), deny)
            .setPublicVersion(NotificationCompat.Builder(c, CH_APPR).setSmallIcon(R.drawable.ic_stat).setColor(ORANGE)
                .setContentTitle(str(R.string.notify_approval_needed)).setContentText(chat?.title ?: HERMES).build())
            .build()
        post(c, nid("a${a.id}"), n)
    }

    /** A Bot answered in a group chat nobody is looking at. Only the newest reply rides along (the SSE event
     *  carries the room's preview, not its history); opening it lands in the room. */
    fun room(c: Context, r: Room) {
        val p = r.preview ?: return
        val me = Person.Builder().setName(str(R.string.common_you)).setKey("me").build()
        val bot = Person.Builder().setName(p.name.ifBlank { "Bot" }).setKey("room:${r.id}:${p.name}").build()
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(r.name).setGroupConversation(true)
            .addMessage(Hub.plain(p.text).take(900), (p.ts * 1000).toLong(), bot)
        val open = PendingIntent.getActivity(c, nid("openroom${r.id}"),
            Intent(c, MainActivity::class.java).putExtra("room", r.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PI)
        val b = NotificationCompat.Builder(c, CH_MSG)
            .setSmallIcon(R.drawable.ic_stat).setColor(LIME).setStyle(style).setNumber(r.unread)
            .setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_MESSAGE)
        post(c, nid("room${r.id}"), b.build())
    }

    fun cancelRoom(c: Context, rid: String) = NotificationManagerCompat.from(c).cancel(nid("room$rid"))

    /** A group chat member's command waits for the owner. Approving needs the biometric proof in the app,
     *  so the notification opens the room instead of answering from the shade. */
    fun roomApproval(c: Context, r: Room, a: RoomAction) {
        val who = r.members.firstOrNull { it.id == a.member }?.name ?: a.member
        val open = PendingIntent.getActivity(c, nid("openroom${r.id}"),
            Intent(c, MainActivity::class.java).putExtra("room", r.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PI)
        val body = listOf(str(R.string.notify_asks_to_run, bidiIsolate(who)), "$ " + a.command.ifBlank { a.description }).joinToString("\n")
        val n = NotificationCompat.Builder(c, CH_APPR)
            .setSmallIcon(R.drawable.ic_stat).setColor(ORANGE)
            .setContentTitle(str(R.string.notify_approve_title, bidiIsolate(r.name))).setContentText(bidiIsolate(who) + ": " + bidiIsolate(a.command.ifBlank { a.description }))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).setAutoCancel(true)
            .setContentIntent(open)
            .setPublicVersion(NotificationCompat.Builder(c, CH_APPR).setSmallIcon(R.drawable.ic_stat).setColor(ORANGE)
                .setContentTitle(str(R.string.notify_approval_needed)).setContentText(r.name).build())
            .build()
        post(c, roomApprovalId(r.id, a), n)
    }

    fun cancelRoomApproval(c: Context, rid: String, a: RoomAction) =
        NotificationManagerCompat.from(c).cancel(roomApprovalId(rid, a))

    private fun roomApprovalId(rid: String, a: RoomAction) = nid("ra$rid:${a.taskId}:${a.generation}:${a.requestId}")

    fun cancelApproval(c: Context, aid: String) = NotificationManagerCompat.from(c).cancel(nid("a$aid"))
    fun cancelChat(c: Context, cid: String) = NotificationManagerCompat.from(c).cancel(nid("c$cid"))
}
