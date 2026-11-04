package dev.avifenesh.hermes

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/** App strings outside composition: toasts, notifications, services, widgets and plain logic.
 * Composables use stringResource/pluralStringResource; everything else resolves through here, so services,
 * workers and Android 12 (whose application context keeps the system language) still speak the app language.
 */
object L10n {
    /** Resolves resource ids. The app installs [AppResolver] at startup; JVM tests install an XML-backed one. */
    interface Resolver {
        fun string(@StringRes id: Int, args: Array<out Any?>): String
        fun plural(@PluralsRes id: Int, count: Int, args: Array<out Any?>): String
        fun locale(): Locale
    }

    @Volatile var resolver: Resolver? = null

    internal fun get(): Resolver = resolver ?: error("L10n used before App.onCreate installed a resolver")

    /** Android resolver over a context that carries the app language ([Locales.localized]). */
    class AppResolver(private val app: Context) : Resolver {
        @Volatile private var cached: Pair<String, Context>? = null

        /** On Android 13+ the application context follows the per-app language; on 12 it is localized here. */
        fun context(): Context {
            val key = Locales.storedTags(app).orEmpty()
            cached?.let { (k, c) -> if (k == key) return c }
            return Locales.localized(app).also { cached = key to it }
        }

        override fun string(id: Int, args: Array<out Any?>): String =
            if (args.isEmpty()) context().getString(id) else context().getString(id, *args)

        override fun plural(id: Int, count: Int, args: Array<out Any?>): String =
            if (args.isEmpty()) context().resources.getQuantityString(id, count)
            else context().resources.getQuantityString(id, count, *args)

        override fun locale(): Locale = context().resources.configuration.locales[0] ?: Locale.getDefault()
    }

    fun install(app: Context) { resolver = AppResolver(app.applicationContext ?: app) }

    /** The context strings and formats come from; Android-only callers (Formatter, DateFormat). */
    fun context(): Context = (resolver as? AppResolver)?.context() ?: Hub.app

    fun locale(): Locale = resolver?.locale() ?: Locale.getDefault()
}

/** Product names go into strings as format arguments so translations cannot alter them.
 * The app's own name is [APP_NAME] (per edition); the user's agent is agentName(). */
internal const val HERMES = "Hermes" // the agent software on the user's machine (gateway, CLI)
internal const val HERMES_AGENT = "Hermes Agent"
internal const val HERMES_DESKTOP = "Hermes Desktop"

/** Resource string in the app language. Format arguments follow the resource's %1$s / %1$d placeholders. */
fun str(@StringRes id: Int, vararg args: Any?): String = L10n.get().string(id, args)

/** Quantity string in the app language; [count] picks the plural form, [args] fill the placeholders. */
fun plural(@PluralsRes id: Int, count: Int, vararg args: Any?): String = L10n.get().plural(id, count, args)
