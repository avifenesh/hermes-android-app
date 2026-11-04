package dev.avifenesh.hermes

/**
 * Whether an approval decision moved a waiting gateway request forward, so Play follows the run it resumes.
 * [resolved] is the gateway's count of resumed requests: 0 when the card was already handled, the request is gone
 * or it expired. An older gateway without the count is judged by the decided state alone.
 */
internal fun approvalResumesRun(resolved: Int?, state: String): Boolean = when {
    state == "expired" -> false
    resolved != null -> resolved > 0
    else -> state == "approved" || state == "denied"
}

/**
 * The app's own label for an approval scope the gateway offers, so the buttons follow the phone's language rather
 * than the gateway's. Null for a scope the app does not know: that one keeps the label the gateway sent.
 */
internal fun approvalChoiceLabel(choice: String): Int? = when (choice) {
    "once" -> R.string.session_allow_once
    "session" -> R.string.session_allow_session
    "always" -> R.string.session_allow_always
    "deny" -> R.string.common_deny
    else -> null
}
