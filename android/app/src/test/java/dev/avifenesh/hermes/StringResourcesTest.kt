package dev.avifenesh.hermes

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Translations must keep the base keys and format placeholders, so a translator cannot break the app's formatting
 * or crash a screen with a missing argument. Runs over every values-<locale>/ folder of main, full and play.
 */
class StringResourcesTest {
    @Test fun everyTranslationHasTheBaseKeysAndPlaceholders() {
        val problems = mutableListOf<String>()
        for (set in SETS) {
            val res = ResDirs.res(set)
            val base = ResFiles.load(res.resolve("values")) ?: continue
            for (dir in ResFiles.localeDirs(res)) {
                val translated = ResFiles.load(dir) ?: continue
                problems += StringRules.problems(base, translated).map { "$set/${dir.name}: $it" }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun baseTextKeepsProductNamesOutOfTranslatableText() {
        val problems = SETS.mapNotNull { set -> ResFiles.load(ResDirs.res(set).resolve("values"))?.let { set to it } }
            .flatMap { (set, base) -> StringRules.baseProblems(base).map { "$set: $it" } }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun checkerCatchesBrokenTranslations() {
        val base = ResFiles.parse("""
            <resources>
                <string name="saved">Saved to %1${'$'}s</string>
                <string name="pair">%1${'$'}s of %2${'$'}d on the gateway</string>
                <string name="brand" translatable="false">Hermes</string>
                <string name="label">Hermes fleet</string>
                <string name="plain">Done</string>
                <plurals name="files"><item quantity="one">%1${'$'}d file</item><item quantity="other">%1${'$'}d files</item></plurals>
            </resources>""")
        val good = ResFiles.parse("""
            <resources>
                <string name="saved">Gespeichert unter %1${'$'}s</string>
                <string name="pair">%2${'$'}d von %1${'$'}s auf dem Gateway</string>
                <string name="plain">Fertig</string>
                <string name="label">Hermes-Flotte</string>
                <plurals name="files"><item quantity="one">Eine Datei</item><item quantity="other">%1${'$'}d Dateien</item></plurals>
            </resources>""")
        assertEquals(emptyList<String>(), StringRules.problems(base, good))
        val bad = ResFiles.parse("""
            <resources>
                <string name="saved">Gespeichert unter %s und %s</string>
                <string name="pair">%1${'$'}s von %2${'$'}s auf dem Server</string>
                <string name="brand">Hermes</string>
                <string name="extra">Neu</string>
                <string name="label">Flotte</string>
                <plurals name="files"><item quantity="one">%2${'$'}d Datei</item><item quantity="few">%1${'$'}d Dateien</item></plurals>
            </resources>""")
        val found = StringRules.problems(base, bad)
        for (expected in listOf("missing string plain", "saved: placeholders", "pair: placeholders", "pair: keep \"gateway\"",
                "brand: marked translatable=\"false\"", "unknown string extra", "label: keep \"Hermes\"", "files[one]: placeholders", "files: no quantity=\"other\"")) {
            assertTrue("expected '$expected' in $found", found.any { it.startsWith(expected) })
        }
        assertEquals(listOf("1\$s", "2\$d"), StringRules.placeholders("%2\$d of %1\$s, 100%%"))
        assertEquals(listOf("1\$s"), StringRules.placeholders("only %s"))
        val named = ResFiles.parse("""<resources><string name="x">Open Hermes to pair</string><string name="y">Restart %1${'$'}s</string></resources>""")
        assertEquals(listOf("x: product name \"Hermes\" in translatable text; pass it as an argument"), StringRules.baseProblems(named))
    }

    companion object { val SETS = listOf("main", "full", "play") }
}

/** One values folder's strings and plurals, merged over its XML files. */
data class ResStrings(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>, val fixed: Set<String>)

object ResFiles {
    private val LOCALE_DIR = Regex("""values-(?:[a-z]{2,3}(?:-r[A-Z]{2})?|b\+[a-z]{2,3}(?:\+[A-Za-z0-9]+)*)""")

    /** values-<locale> folders (values-night, values-v31 and the like are not locales). */
    fun localeDirs(res: File): List<File> =
        res.listFiles { f -> f.isDirectory && LOCALE_DIR.matches(f.name) }.orEmpty().sortedBy { it.name }

    fun load(dir: File): ResStrings? {
        val files = dir.listFiles { f -> f.extension == "xml" }.orEmpty().sortedBy { it.name }
        if (files.isEmpty()) return null
        val parts = files.map { parse(it.readText()) }
        return ResStrings(parts.flatMap { it.strings.entries }.associate { it.toPair() },
            parts.flatMap { it.plurals.entries }.associate { it.toPair() }, parts.flatMap { it.fixed }.toSet())
    }

    fun parse(xml: String): ResStrings {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.trimIndent().byteInputStream()).documentElement
        val strings = mutableMapOf<String, String>()
        val plurals = mutableMapOf<String, Map<String, String>>()
        val fixed = mutableSetOf<String>()
        val nodes = root.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            val name = e.getAttribute("name")
            when (e.tagName) {
                "string" -> {
                    strings[name] = TestStrings.unescape(e.textContent)
                    if (e.getAttribute("translatable") == "false") fixed += name
                }
                "plurals" -> {
                    val items = e.getElementsByTagName("item")
                    plurals[name] = (0 until items.length).associate { k ->
                        val item = items.item(k) as Element
                        item.getAttribute("quantity") to TestStrings.unescape(item.textContent)
                    }
                }
            }
        }
        return ResStrings(strings, plurals, fixed)
    }
}

/** The rules a translation is held to. Kept pure so [StringResourcesTest] can prove the checker itself. */
object StringRules {
    private val SPEC = Regex("""%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])""")

    /** Terms translators keep as is (docs/translations.md). Checked case-insensitively, as a substring. */
    val KEEP = listOf("gateway", "Bot", "MCP")

    /** Product names that must arrive as arguments, never as translatable text. */
    private val PRODUCTS = listOf("Hermes", "Herms")

    /** Strings referenced from the manifest or XML, which cannot take arguments, so the name stays in the text
     * (translators keep it): widget and accessibility labels. report_subject predates the rule. */
    private val NAMED = setOf("widget_label", "phone_service_label", "phone_service_desc", "report_subject")

    /** Positional placeholders in order of index; plain %s / %d count as %1$s, %2$d... by position. */
    fun placeholders(text: String): List<String> {
        var plain = 0
        return SPEC.findAll(text).filter { it.groupValues[2] != "%" }.map { m ->
            val index = m.groupValues[1].ifEmpty { (++plain).toString() }
            index + "$" + m.groupValues[2].lowercase()
        }.distinct().sortedBy { it.substringBefore('$').toInt() }.toList()
    }

    private fun positional(text: String) = SPEC.findAll(text).filter { it.groupValues[2] != "%" }.all { it.groupValues[1].isNotEmpty() }

    fun problems(base: ResStrings, t: ResStrings): List<String> {
        val out = mutableListOf<String>()
        val wanted = base.strings.keys - base.fixed
        for (k in (wanted - t.strings.keys).sorted()) out += "missing string $k"
        for (k in (t.strings.keys - base.strings.keys).sorted()) out += "unknown string $k"
        for (k in (base.plurals.keys - t.plurals.keys).sorted()) out += "missing plurals $k"
        for (k in (t.plurals.keys - base.plurals.keys).sorted()) out += "unknown plurals $k"
        for (k in (t.strings.keys intersect base.fixed).sorted()) out += "$k: marked translatable=\"false\" in the base; do not translate it"
        for (k in (t.strings.keys intersect wanted).sorted()) {
            val b = base.strings.getValue(k)
            val s = t.strings.getValue(k)
            val pb = placeholders(b)
            val ps = placeholders(s)
            if (pb != ps || (ps.size > 1 && !positional(s))) out += "$k: placeholders $ps, base has $pb (use positional %1\$s...)"
            keep(k, b, s)?.let(out::add)
        }
        for (k in (t.plurals.keys intersect base.plurals.keys).sorted()) {
            val b = base.plurals.getValue(k)
            val forms = t.plurals.getValue(k)
            val pb = placeholders(b["other"] ?: b.values.first())
            if ("other" !in forms) out += "$k: no quantity=\"other\""
            for ((q, s) in forms) {
                val ps = placeholders(s)
                // A form may drop the count ("one" -> "Eine Datei"); it may not invent or retype one.
                if (!pb.containsAll(ps) || (q == "other" && ps != pb)) out += "$k[$q]: placeholders $ps, base has $pb"
                keep("$k[$q]", b["other"].orEmpty(), s)?.let(out::add)
            }
        }
        return out
    }

    private fun keep(k: String, base: String, s: String): String? {
        // Names that had to stay in the text (manifest labels) survive translation verbatim.
        PRODUCTS.firstOrNull { Regex("""\b$it\b""").containsMatchIn(base) && it !in s }?.let { return "$k: keep \"$it\" as is" }
        val term = KEEP.firstOrNull { Regex("""\b${Regex.escape(it)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(base) && !s.contains(it, ignoreCase = true) }
        return term?.let { "$k: keep \"$it\" untranslated" }
    }

    fun baseProblems(base: ResStrings): List<String> {
        val texts = (base.strings - base.fixed - NAMED).entries.map { it.key to it.value } +
            base.plurals.flatMap { (k, v) -> v.values.map { k to it } }
        return texts.mapNotNull { (k, v) ->
            PRODUCTS.firstOrNull { Regex("""\b$it\b""").containsMatchIn(v) }?.let { "$k: product name \"$it\" in translatable text; pass it as an argument" }
        }.distinct()
    }
}
