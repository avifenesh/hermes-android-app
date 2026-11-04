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
