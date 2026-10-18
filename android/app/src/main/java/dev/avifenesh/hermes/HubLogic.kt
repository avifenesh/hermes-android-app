package dev.avifenesh.hermes

import java.net.URLEncoder

/** Pure helpers for the Hub screens (no Android types, so they run in JVM unit tests). */
object HubLogic {
    /** The biometric-signed key for a hub write; the server rebuilds the same string (server/mobile/market.py). */
    fun guardKey(kind: String, action: String, target: String, accept: Boolean = false): String =
        "$kind:$action:$target" + if (accept) ":accept" else ""

    enum class Gate { SCANNING, ALLOW, REFUSED, SCAN_FAILED }

    /** Install is offered only when Hermes' own scan policy says "allow". "ask" and "block" have no override here. */
    fun gate(policy: String?, scanFailed: Boolean): Gate = when {
        scanFailed -> Gate.SCAN_FAILED
        policy.isNullOrBlank() -> Gate.SCANNING
        policy == "allow" -> Gate.ALLOW
        else -> Gate.REFUSED
    }

    val SEVERITIES = listOf("critical", "high", "medium", "low")

    fun findingsLine(counts: Map<String, Int>): String =
        SEVERITIES.filter { (counts[it] ?: 0) > 0 }.joinToString(" · ") { "${counts[it]} $it" }.ifEmpty { "no findings" }

    fun logTail(log: String, lines: Int = 14): String = log.trimEnd().lines().takeLast(lines).joinToString("\n")

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** "" means the gateway's own profile, so the param is left off. */
    fun withProfile(path: String, profile: String): String =
        if (profile.isBlank()) path else path + (if ('?' in path) "&" else "?") + "profile=" + enc(profile)

    /** Required env names still empty, ignoring ones the machine already has. */
    fun missingEnv(required: List<Pair<String, Boolean>>, values: Map<String, String>, present: Set<String> = emptySet()): List<String> =
        required.filter { (name, req) -> req && values[name].isNullOrBlank() && name !in present }.map { it.first }

    /** Shelves for the official catalog: categories by size, then name; items by name. */
    fun <T> shelves(items: List<T>, category: (T) -> String, name: (T) -> String): List<Pair<String, List<T>>> =
        items.groupBy { category(it).ifBlank { "general" } }.toList()
            .sortedWith(compareByDescending<Pair<String, List<T>>> { it.second.size }.thenBy { it.first })
            .map { (k, v) -> k to v.sortedBy(name) }

    /** One predicate for browse and search; discovery never promotes already installed items. */
    data class Filters(
        val scope: String = "discover",
        val source: String = "all",
        val category: String = "all",
        val query: String = "",
    ) {
        fun matches(installed: Boolean, itemSource: String, itemCategory: String, text: String): Boolean =
            (scope == "all" || if (scope == "installed") installed else !installed) &&
                (source == "all" || source == itemSource) &&
                (category == "all" || category == itemCategory.ifBlank { "general" }) &&
                (query.isBlank() || text.contains(query.trim(), ignoreCase = true))
    }

    fun trustLabel(t: String): String = when (t) { "builtin" -> "official"; "trusted" -> "trusted"; "community" -> "community"; else -> t }

    fun pretty(slug: String): String = slug.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }
}
