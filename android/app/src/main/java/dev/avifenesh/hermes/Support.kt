@file:OptIn(ExperimentalFoundationApi::class)

package dev.avifenesh.hermes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Parcel
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Pure, bounded text shared by the reviewed publisher report and email alternative. */
internal object SupportPayload {
    // Binder has a shared 1 MiB buffer, not a per-intent allowance:
    // https://developer.android.com/reference/android/os/TransactionTooLargeException
    // 8 KiB of UTF-8 becomes at most 24 KiB of percent-encoded ASCII, or 48 KiB
    // in a UTF-16 Parcel. Leave room under a 64 KiB intent budget for its envelope.
    const val BODY_UTF8_LIMIT = 8 * 1024
    const val INTENT_BYTES_LIMIT = 64 * 1024
    const val SHORTENED = "\n\n[Report shortened to fit the 8 KiB limit. Only the text shown here is included.]"

    fun prefixUtf8(text: String, budget: Int): String {
        require(budget >= 0)
        var end = 0
        var bytes = 0
        while (end < text.length) {
            val cp = text.codePointAt(end)
            val size = when { cp <= 0x7f -> 1; cp <= 0x7ff -> 2; cp <= 0xffff -> 3; else -> 4 }
            if (bytes + size > budget) break
            bytes += size
            end += Character.charCount(cp)
        }
        return text.substring(0, end)
    }

    fun body(note: String, quoted: String?, diagnostics: String): String {
        val full = buildString {
            append("What happened:\n").append(note.trim().ifBlank { "(not provided)" })
            append("\n\n---\n").append(diagnostics)
            if (!quoted.isNullOrBlank()) append("\n\nSelected message:\n").append(quoted)
            append("\n\nPrepared and reviewed by me for voluntary support reporting.")
        }
        if (full.toByteArray(Charsets.UTF_8).size <= BODY_UTF8_LIMIT) return full
        return prefixUtf8(full, BODY_UTF8_LIMIT - SHORTENED.toByteArray(Charsets.UTF_8).size) + SHORTENED
    }
}

/** The mail subject names this edition's app and stays fixed text in every language. The report strings take the
 * publisher's name ([EDITION_PUBLISHER]) as an argument: support_disclosure, support_received, support_row_sub. */
internal object SupportCopy {
    const val SUBJECT = "$APP_NAME issue report"
}

/** Lives only in this process. Never save the selected message or clipboard callback in a Bundle. */
internal data class SupportRequest(
    val generation: Long,
    val route: Route?,
    val tab: String,
    val text: String? = null,
    val id: String = UUID.randomUUID().toString(),
)

internal object Support {
    fun current(request: SupportRequest): Boolean = Prefs.paired && Ui.paired.value && Ui.pairLink.value == null &&
        request.generation == Api.epoch.capture() && request.route == Ui.stack.lastOrNull() && request.tab == Ui.tab.value

    /** A long-pressed message opens actions only over the conversation that shows it: a mobile chat, or another
     *  app's session (SessionScreen rows carry the session id as chatId). */
    fun owns(route: Route?, message: Msg): Boolean = when (route) {
        is Route.ChatR -> route.id == message.chatId
        is Route.SessR -> route.id == message.chatId
        else -> false
    }

    fun open(message: Msg? = null) {
        synchronized(Api.epoch) {
            if (!Prefs.paired || !Ui.paired.value || Ui.pairLink.value != null) return
            val route = Ui.stack.lastOrNull()
            // Do not let an outgoing animated chat open actions over a different chat.
            if (message != null && !owns(route, message)) return
            if (message == null && (route != null || Ui.tab.value != "me")) return
            Ui.supportRequest.value = SupportRequest(Api.epoch.capture(), route, Ui.tab.value, message?.content)
        }
    }

    /** Fence support actions and callbacks against machine changes, including switch-away/back. */
    fun act(request: SupportRequest, action: () -> Unit): Boolean {
        synchronized(Api.epoch) {
            if (Ui.supportRequest.value?.id != request.id || !current(request)) {
                if (Ui.supportRequest.value?.id == request.id) Ui.supportRequest.value = null
                return false
            }
            Api.epoch.commit(request.generation) { action() }
            return true
        }
    }

    fun intent(ctx: Context, body: String): Intent {
        // Keep mailto opaque and put user text in Android's standard extras.
        // Uri.Builder loses an opaque recipient; legacy MailTo query parsing
        // decodes '&' before splitting headers and can truncate a quoted body.
        val email = ctx.getString(R.string.report_email)
        return Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(email)))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            .putExtra(Intent.EXTRA_SUBJECT, SupportCopy.SUBJECT)
            .putExtra(Intent.EXTRA_TEXT, body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun openMail(ctx: Context, body: String): Boolean = runCatching {
        val intent = intent(ctx, body)
        val parcel = Parcel.obtain()
        try {
            intent.writeToParcel(parcel, 0)
            check(parcel.dataSize() <= SupportPayload.INTENT_BYTES_LIMIT)
        } finally { parcel.recycle() }
        ctx.startActivity(intent)
    }.isSuccess
}

/** Root-level host: survives rotation, but never revives a draft after process death or route exit. */
@Composable
internal fun SupportDialogs() {
    // Hub.reset publishes fresh metadata after invalidating Api.epoch. Observe it even if
    // the same route/tab stays selected during a machine switch; epoch itself is not State.
    val metadata by Hub.meta.collectAsState()
    val request = Ui.supportRequest.value ?: return
    val valid = remember(request, metadata, Ui.paired.value, Ui.pairLink.value, Ui.stack.lastOrNull(), Ui.tab.value) { Support.current(request) }
    if (!valid) {
        LaunchedEffect(request.id) { if (Ui.supportRequest.value?.id == request.id) Ui.supportRequest.value = null }
        return
    }
    key(request.id) { SupportDialog(request) }
}

@Composable
internal fun SupportDialog(
    request: SupportRequest,
    sendReport: suspend (PublisherReportDraft) -> Unit = { PublisherReports.send(it) },
) {
    val ctx = LocalContext.current
    val cb = LocalClipboardManager.current
    val p = P
    var stage by rememberSaveable { mutableStateOf(if (request.text == null) "edit" else "actions") }
    var note by rememberSaveable { mutableStateOf("") }
    var include by rememberSaveable { mutableStateOf(false) }
    var noteTooLong by rememberSaveable { mutableStateOf(false) }
    var mailError by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var sendJob by remember { mutableStateOf<Job?>(null) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(request.id, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) sendJob?.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); sendJob?.cancel() }
    }
    val dismiss = {
        sendJob?.cancel()
        if (Ui.supportRequest.value?.id == request.id) Ui.supportRequest.value = null
    }
    if (stage == "actions") {
        HDialog(stringResource(R.string.support_message_actions), onDismiss = dismiss, body = {
            Text(stringResource(R.string.support_actions_body), style = sans(13.5f, 400, p.tx2, 19f))
        }, actions = {
            Btn(stringResource(R.string.common_cancel), primary = false) { dismiss() }
            Btn(stringResource(R.string.common_copy), primary = false) {
                Support.act(request) { cb.setText(AnnotatedString(request.text.orEmpty())); dismiss(); Ui.say(R.string.common_copied) }
            }
            Btn(stringResource(R.string.support_report)) { Support.act(request) { stage = "edit" } }
        })
        return
    }
    val diagnostics = remember(request.id) {
        buildString {
            append("App: $EDITION_NAME ").append(runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty())
            append("\nPackage: ").append(ctx.packageName)
            append("\nAndroid: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(')')
            append("\nDevice: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        }
    }
    val body = remember(note, include, diagnostics, request.id) { SupportPayload.body(note, if (include) request.text else null, diagnostics) }
    val draftId = rememberSaveable(body) { UUID.randomUUID().toString() }
    val draft = remember(body, draftId) { PublisherReportDraft(body, draftId) }
    var received by rememberSaveable(draftId) { mutableStateOf(false) }
    HDialog(if (stage == "preview") stringResource(R.string.support_review_title) else stringResource(if (request.text == null) R.string.report_problem else R.string.report_message), onDismiss = dismiss, body = {
        Text(stringResource(R.string.support_disclosure, EDITION_PUBLISHER), style = sans(13.5f, 400, p.tx2, 19f))
        Spacer(Modifier.height(12.dp))
        Btn(stringResource(R.string.support_privacy_policy), Modifier.fillMaxWidth(), primary = false, enabled = !sending) {
            Support.act(request) { Ui.push(Route.PrivacyR) }
        }
        Text(stringResource(R.string.support_policy_closes_draft), style = sans(12.5f, 400, p.tx2, 18f))
        Spacer(Modifier.height(12.dp))
        if (stage == "preview") {
            Text(stringResource(R.string.support_endpoint, PublisherReports.ENDPOINT), style = sans(12.5f, 400, p.tx2, 18f))
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(stringResource(R.string.support_report_id, draft.id), style = mono(12.5f, 500, p.tx2))
            }
            Text(stringResource(R.string.support_keep_id), style = sans(12.5f, 400, p.tx2, 18f))
            Text(stringResource(R.string.support_email_alternative, stringResource(R.string.report_email)), style = sans(14f, 600, p.tx))
            Spacer(Modifier.height(12.dp))
            // No ellipsis or collapsed section: both destinations get exactly this text.
            Text(body, style = sans(13.5f, 400, p.tx, 19f))
            if (received) Text(stringResource(R.string.support_received, EDITION_PUBLISHER), style = sans(13.5f, 600, p.tx2, 19f))
            if (sendError) Text(stringResource(R.string.support_send_failed), style = sans(13.5f, 600, p.tx2, 19f))
            if (mailError) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.support_mail_failed, stringResource(R.string.report_email)), style = sans(13.5f, 600, p.tx2, 19f))
            }
        } else {
            InputBox(note, { value ->
                noteTooLong = value.toByteArray(Charsets.UTF_8).size > SupportPayload.BODY_UTF8_LIMIT
                if (!noteTooLong) note = value
            }, stringResource(R.string.support_note_hint), Modifier.fillMaxWidth(), minLines = 3, maxLines = 6, size = 14.5f)
            if (noteTooLong) Text(stringResource(R.string.support_note_too_long), style = sans(12.5f, 500, p.tx2))
            Text(stringResource(R.string.support_included), style = sans(12.5f, 400, p.mu, 18f))
            if (!request.text.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                val includeLabel = stringResource(R.string.report_include)
                Text(includeLabel, style = sans(14.5f, 600, p.tx), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(include, role = Role.Switch) { include = !include }.padding(horizontal = 2.dp, vertical = 12.dp))
                Text(stringResource(R.string.support_include_detail), style = sans(12.5f, 400, p.mu, 18f))
            } else Text(stringResource(R.string.support_no_messages), style = sans(12.5f, 400, p.mu, 18f))
        }
    }, actions = {
        Btn(stringResource(R.string.common_cancel), primary = false) { dismiss() }
        if (stage == "preview") {
            Btn(stringResource(R.string.common_edit), primary = false, enabled = !sending) { Support.act(request) { stage = "edit"; mailError = false; sendError = false } }
            Btn(stringResource(R.string.support_open_mail), primary = false, enabled = !sending) {
                Support.act(request) {
                    if (Support.openMail(ctx, body)) dismiss() else mailError = true
                }
            }
            Btn(stringResource(if (sending) R.string.support_sending else if (received) R.string.support_received_button else R.string.support_send), enabled = !sending && !received) {
                Support.act(request) {
                    // Set synchronously before launching: duplicate taps cannot enqueue uploads.
                    if (!sending && !received) {
                        sending = true
                        sendError = false
                        sendJob = scope.launch {
                            try {
                                if (!Support.act(request) {}) return@launch
                                sendReport(draft)
                                Support.act(request) { received = true }
                            } catch (e: CancellationException) {
                                Support.act(request) { sendError = true }
                                throw e
                            } catch (_: Exception) {
                                Support.act(request) { sendError = true }
                            } finally {
                                Support.act(request) { sending = false }
                            }
                        }
                    }
                }
            }
        } else Btn(stringResource(R.string.support_review_button)) { Support.act(request) { stage = "preview" } }
    })
}

/** General support never looks through the user's loaded chats for a quote. */
@Composable
fun ReportProblemRow() {
    Panel { NavRow(Ic.shield, stringResource(R.string.report_problem), stringResource(R.string.support_row_sub, EDITION_PUBLISHER)) { Support.open() } }
}

/** Long-press only opens the themed action chooser. Clipboard writes require a separate Copy tap. */
@Composable
fun Modifier.reportable(message: Msg): Modifier {
    val haptic = LocalHapticFeedback.current
    return if (message.content.isBlank()) this else combinedClickable(onClick = {}, onLongClickLabel = stringResource(R.string.support_message_actions), onLongClick = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        Support.open(message)
    })
}
