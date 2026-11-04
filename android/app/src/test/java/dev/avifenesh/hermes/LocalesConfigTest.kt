package dev.avifenesh.hermes

import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** res/xml/locales_config.xml (what system Settings and the in-app picker offer) must match the translations that ship. */
class LocalesConfigTest {
    companion object {
        /** Declared language -> resource folder. Android looks up Hebrew under the legacy code (values-iw) and serves
         * zh-Hans / zh-Hant from the region folders libraries use (zh-rCN, zh-rTW; any Hant region falls back to zh-rTW). */
        val FOLDER = mapOf(
            "en" to "values", "af" to "values-af", "ar" to "values-ar", "de" to "values-de", "es" to "values-es",
            "fr" to "values-fr", "ga" to "values-ga", "he" to "values-iw", "hu" to "values-hu", "it" to "values-it",
            "ja" to "values-ja", "ko" to "values-ko", "pt" to "values-pt", "ru" to "values-ru", "tr" to "values-tr",
            "uk" to "values-uk", "zh-Hans" to "values-zh-rCN", "zh-Hant" to "values-zh-rTW",
        )

        /** Declared but not translated yet: these fall back to English. A translation's change removes its line here. */
        val PENDING = emptySet<String>()

        fun declared(): List<String> {
            val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
                .parse(ResDirs.res("main").resolve("xml/locales_config.xml")).documentElement
            val nodes = root.getElementsByTagName("locale")
            return (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS("http://schemas.android.com/apk/res/android", "name") }
        }

        /** values-<locale> folders that hold strings, per source set. */
        fun shipped(set: String): Set<String> = ResFiles.localeDirs(ResDirs.res(set))
            .filter { d -> d.listFiles { f -> f.extension == "xml" }.orEmpty().any { "<string" in it.readText() || "<plurals" in it.readText() } }
            .map { it.name }.toSet()
    }

    @Test fun declaredLanguagesMatchTheShippedFolders() {
        val declared = declared()
        assertEquals("duplicate locale in locales_config.xml", declared.size, declared.toSet().size)
        assertEquals("English (res/values) is the fallback and comes first", "en", declared.first())
        val unknown = declared - FOLDER.keys
        assertTrue("no resource folder known for $unknown; add it to FOLDER with Android's qualifier", unknown.isEmpty())
        val byFolder = FOLDER.entries.associate { (tag, dir) -> dir to tag }
        val folders = shipped("main")
        val strays = folders - byFolder.keys
        assertTrue("translation folders with no declared language (Hebrew is values-iw, Chinese values-zh-rCN/-rTW): $strays", strays.isEmpty())
        val translated = folders.map { byFolder.getValue(it) }.toSet()
        assertEquals("translations that ship must be declared, and PENDING must list exactly the declared rest",
            declared.toSet() - "en", translated + PENDING)
        assertTrue("PENDING still lists translated languages: ${PENDING intersect translated}", (PENDING intersect translated).isEmpty())
        assertTrue("PENDING lists undeclared languages: ${PENDING - declared.toSet()}", declared.toSet().containsAll(PENDING))
    }

    @Test fun flavorTranslationsFollowMainLanguages() {
        val main = shipped("main")
        for (set in listOf("full", "play")) {
            // An edition with its own English strings needs the same languages, or lint's MissingTranslation fails.
            val own = ResFiles.load(ResDirs.res(set).resolve("values"))?.let { it.strings.keys - it.fixed + it.plurals.keys }.orEmpty()
            assertEquals("$set must translate exactly the languages main ships", if (own.isEmpty()) emptySet() else main, shipped(set))
        }
    }

    @Test fun everyDeclaredLanguageIsNamedInItself() {
        val names = declared().associateWith { LanguageNames.native(it) }
        assertEquals("עברית", names["he"])
        assertEquals("العربية", names["ar"])
        assertEquals("Deutsch", names["de"])
        assertEquals("日本語", names["ja"])
        assertEquals("简体中文", names["zh-Hans"])
        assertEquals("繁體中文", names["zh-Hant"])
        for ((tag, name) in names) {
            assertTrue("no name for $tag", name.isNotBlank())
            assertNotEquals("raw tag shown for $tag", tag, name)
        }
        assertEquals("all names distinct", names.size, names.values.toSet().size)
    }

    @Test fun storedChoicesResolveToTheOfferedLanguage() {
        val offered = declared()
        assertEquals("he", LanguageNames.match("he-IL", offered))
        assertEquals("he", LanguageNames.match("iw", offered))
        assertEquals("zh-Hant", LanguageNames.match("zh-Hant-TW", offered))
        assertEquals("zh-Hant", LanguageNames.match("zh-HK", offered))
        assertEquals("zh-Hans", LanguageNames.match("zh-CN", offered))
        assertEquals("zh-Hans", LanguageNames.match("zh", offered))
        assertEquals("pt", LanguageNames.match("pt-BR", offered))
        assertEquals("en", LanguageNames.match("en-US,fr", offered))
        assertNull(LanguageNames.match("nl-NL", offered))
        assertNull(LanguageNames.match(null, offered))
    }
}
