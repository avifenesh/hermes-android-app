package dev.avifenesh.hermes

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** JVM tests resolve [str]/[plural] against the real English resources, so assertions read the shipped text.
 * Call [install] from a test's setup. The flavor overlay (src/full or src/play) is the one compiled into the test.
 */
object TestStrings {
    private val names: Map<Int, String> by lazy {
        fun ids(kind: String) = Class.forName("dev.avifenesh.hermes.R\$$kind").fields.associate { it.getInt(null) to it.name }
        ids("string") + ids("plurals")
    }

    private val flavor: String by lazy {
        if (runCatching { Class.forName("dev.avifenesh.hermes.UnsupportedPhoneControl") }.isSuccess) "play" else "full"
    }

    val strings: Map<String, String> by lazy { load().first }
    val plurals: Map<String, Map<String, String>> by lazy { load().second }

    private fun load(): Pair<Map<String, String>, Map<String, Map<String, String>>> {
        val s = mutableMapOf<String, String>()
        val p = mutableMapOf<String, Map<String, String>>()
        for (set in listOf("main", flavor)) {
            val dir = ResDirs.res(set).resolve("values")
            for (f in dir.listFiles { x -> x.extension == "xml" }.orEmpty().sortedBy { it.name }) {
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).documentElement
                val nodes = doc.childNodes
                for (i in 0 until nodes.length) {
                    val e = nodes.item(i) as? Element ?: continue
                    when (e.tagName) {
                        "string" -> s[e.getAttribute("name")] = unescape(e.textContent)
                        "plurals" -> {
                            val items = e.getElementsByTagName("item")
                            p[e.getAttribute("name")] = (0 until items.length).associate {
                                val item = items.item(it) as Element
                                item.getAttribute("quantity") to unescape(item.textContent)
                            }
                        }
                    }
                }
            }
        }
        return s to p
    }

    /** Android's string resource rules: outer quotes keep whitespace, otherwise runs collapse; backslash escapes. */
    fun unescape(raw: String): String {
        val quoted = raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")
        val t = if (quoted) raw.substring(1, raw.length - 1) else raw.trim().replace(Regex("\\s+"), " ")
        val out = StringBuilder()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '\\' && i + 1 < t.length) {
                val n = t[i + 1]
                when (n) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'u' -> { out.append(t.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                    else -> out.append(n)
                }
                i += 2; continue
            }
            out.append(c); i++
        }
        return out.toString()
    }

    private fun format(text: String, args: Array<out Any?>) = if (args.isEmpty()) text else String.format(Locale.US, text, *args)

    val resolver = object : L10n.Resolver {
        override fun string(id: Int, args: Array<out Any?>): String =
            format(strings[names[id]] ?: error("no string for id $id"), args)

        override fun plural(id: Int, count: Int, args: Array<out Any?>): String {
            val forms = plurals[names[id]] ?: error("no plurals for id $id")
            return format(forms[if (count == 1) "one" else "other"] ?: forms.getValue("other"), args)
        }

        override fun locale(): Locale = Locale.US
    }

    fun install() { L10n.resolver = resolver }
}

/** The module's resource folders, whatever directory Gradle runs the tests from. */
object ResDirs {
    val src: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }.map { it.resolve("src") }
            .firstOrNull { it.resolve("main/res/values").isDirectory } ?: error("src/main/res not found from ${File("").absolutePath}")
    }

    fun res(set: String): File = src.resolve("$set/res")
}
