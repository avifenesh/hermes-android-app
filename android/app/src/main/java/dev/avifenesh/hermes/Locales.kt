package dev.avifenesh.hermes

import android.app.Activity
import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import org.xmlpull.v1.XmlPullParser
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

/** Per-app language. Android 13+ keeps the choice in LocaleManager (shared with system Settings, which recreates
 * the activities). Android 12 has no per-app language: the choice lives in our own preferences, activities
 * attach a localized base context, and [L10n] resolves strings for services and notifications through
 * [localized]. The languages offered come from res/xml/locales_config.xml, the same file the system reads.
 */
object Locales {
    private const val PREFS = "app_locale"
    private const val KEY = "tags"
    private val live: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap())

    /** Language tags declared in locales_config.xml, in file order. */
    fun supported(c: Context): List<String> {
        val out = mutableListOf<String>()
        c.resources.getXml(R.xml.locales_config).use { x ->
            while (x.next() != XmlPullParser.END_DOCUMENT) {
                if (x.eventType == XmlPullParser.START_TAG && x.name == "locale") {
                    x.getAttributeValue("http://schemas.android.com/apk/res/android", "name")?.let(out::add)
                }
            }
        }
        return out
    }

    /** The chosen language tags, or null when the app follows the system language. */
    fun current(c: Context): String? =
        if (Build.VERSION.SDK_INT >= 33) {
            c.getSystemService(LocaleManager::class.java).applicationLocales.takeUnless { it.isEmpty }?.toLanguageTags()
        } else storedTags(c)

    /** The offered language the current choice resolves to (null: system default or unknown). */
    fun currentOffered(c: Context): String? = LanguageNames.match(current(c), supported(c))

    /** Android 12 storage only; on 13+ the system owns the choice. Not part of [Prefs], so unpairing keeps it. */
    fun storedTags(c: Context): String? =
        if (Build.VERSION.SDK_INT >= 33) null
        else c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { it.isNotBlank() }

    /** Apply a language (null = system default). 13+: the system recreates activities; 12: we do. */
    fun set(c: Context, tag: String?) {
        if (Build.VERSION.SDK_INT >= 33) {
            c.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply { if (tag == null) remove(KEY) else putString(KEY, tag) }.apply()
        applyDefault(c)
        relocalized(c.applicationContext)
        synchronized(live) { live.toList() }.filter { it is MainActivity || it is WakeActivity }.forEach { it.recreate() }
    }

    /** After a language change: rename the notification channels, redraw the widget and the Talk tile. The launcher
     * and System UI render those with the system language unless the app sets the text itself. */
    fun relocalized(app: Context) {
        runCatching {
            Notify.channels(app)
            WakeListen.channels(app)
            val nm = app.getSystemService(android.app.NotificationManager::class.java)
            nm.getNotificationChannel(Wake.CH)?.let { ch ->
                ch.name = str(R.string.wake_channel); ch.description = str(R.string.wake_channel_desc); nm.createNotificationChannel(ch)
            }
            Widget.update(app)
            android.service.quicksettings.TileService.requestListeningState(app, android.content.ComponentName(app, TalkTile::class.java))
        }
    }

    private var seen: String? = null

    /** Application.onConfigurationChanged hook: Android 13+ delivers a per-app or system language change here. */
    fun configurationChanged(app: Context, conf: Configuration) {
        val tags = conf.locales.toLanguageTags()
        if (seen != null && tags != seen) relocalized(app)
        seen = tags
    }

    /** A context whose resources use the chosen language; the plain context on 13+ or with no choice. */
    fun localized(c: Context): Context {
        val tags = storedTags(c) ?: return c
        val conf = Configuration(c.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tags)) }
        return c.createConfigurationContext(conf)
    }

    /** Activity.attachBaseContext hook. A no-op on 13+, where the framework already applies the per-app language. */
    fun wrap(base: Context): Context = localized(base)

    /** Android 12: Locale.getDefault() and formatting follow the chosen language too. */
    private fun applyDefault(c: Context) {
        if (Build.VERSION.SDK_INT >= 33) return
        val tags = storedTags(c)
        LocaleList.setDefault(if (tags != null) LocaleList.forLanguageTags(tags) else Configuration(c.applicationContext.resources.configuration).locales)
    }

    fun init(app: Application) {
        seen = app.resources.configuration.locales.toLanguageTags()
        // A choice stored while the phone ran Android 12 moves to the system once it runs 13+.
        if (Build.VERSION.SDK_INT >= 33) {
            val old = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            old.getString(KEY, null)?.let { tags ->
                val lm = app.getSystemService(LocaleManager::class.java)
                if (lm.applicationLocales.isEmpty) lm.applicationLocales = LocaleList.forLanguageTags(tags)
                old.edit().remove(KEY).apply()
            }
        } else if (storedTags(app) != null) applyDefault(app)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) { synchronized(live) { live.add(a) } }
            override fun onActivityDestroyed(a: Activity) { synchronized(live) { live.remove(a) } }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        })
    }

    /** "עברית", "Deutsch": each language named in itself, capitalized by its own rules. */
    fun nativeName(tag: String): String = LanguageNames.native(tag)

    /** The language's name in the current UI language, for the secondary line. */
    fun localName(tag: String, ui: Locale): String = LanguageNames.inLocale(tag, ui)
}

/** Pure naming rules (JVM-testable). Chinese is named by script, which is what the two variants differ in. */
object LanguageNames {
    private val scripts = mapOf("zh-Hans" to "简体中文", "zh-Hant" to "繁體中文")

    fun native(tag: String): String = scripts[tag] ?: Locale.forLanguageTag(tag).let { l -> cap(l.getDisplayName(l), l) }

    fun inLocale(tag: String, ui: Locale): String = cap(Locale.forLanguageTag(tag).getDisplayName(ui), ui)

    /** The offered tag a stored choice means. System Settings may store "he-IL" or "zh-Hant-TW" for "he" / "zh-Hant". */
    fun match(chosen: String?, offered: List<String>): String? {
        if (chosen.isNullOrBlank()) return null
        val first = chosen.substringBefore(',')
        offered.firstOrNull { it.equals(first, ignoreCase = true) }?.let { return it }
        val l = Locale.forLanguageTag(first)
        return offered.firstOrNull { o -> Locale.forLanguageTag(o).let { it.language == l.language && script(it) == script(l) } }
    }

    /** Chinese variants differ by script; a region implies one (TW/HK/MO: Traditional). Other languages: none. */
    private fun script(l: Locale): String = when {
        l.language != "zh" -> ""
        l.script.isNotEmpty() -> l.script
        l.country in setOf("TW", "HK", "MO") -> "Hant"
        else -> "Hans"
    }

    private fun cap(s: String, l: Locale): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(l) else it.toString() }
}
