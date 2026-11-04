package dev.avifenesh.hermes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.intl.LocaleList as ComposeLocales
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Look tab: the app language, in a Section + Panel row like the rest of This phone. */
internal fun LazyListScope.languageSettings() {
    item { Section(stringResource(R.string.language_section), null) }
    item { Panel { LanguageRow() } }
}

@Composable
private fun LanguageRow() {
    val ctx = LocalContext.current
    val ui = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    var open by rememberSaveable { mutableStateOf(false) }
    // Re-read after every pick: on 13+ switching between System default and an explicit language with the same
    // effective locale changes no configuration, so keying on the UI locale alone would keep a stale choice.
    var picks by remember { mutableIntStateOf(0) }
    val chosen = remember(ui, picks) { Locales.current(ctx) }
    val current = remember(ui, picks) { Locales.currentOffered(ctx) }
    // A language set elsewhere that the app does not offer still shows by name, with no radio selected.
    val sub = (current ?: chosen)?.let { Locales.nativeName(it.substringBefore(',')) } ?: stringResource(R.string.language_system_default)
    SettingRow(stringResource(R.string.language_title), sub) {
        Btn(stringResource(R.string.language_change), primary = false) { open = true }
    }
    if (open) LanguageDialog(current, chosen == null, ui, onDismiss = { open = false }) { tag ->
        open = false
        if (tag != current || (tag == null) != (chosen == null)) Locales.set(ctx, tag)
        picks++
    }
}

@Composable
private fun LanguageDialog(current: String?, system: Boolean, ui: Locale, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val ctx = LocalContext.current
    val tags = remember { Locales.supported(ctx) }
    // The system language, named in itself, under "System default".
    val device = remember { android.content.res.Resources.getSystem().configuration.locales[0] }
    HDialog(stringResource(R.string.language_title), onDismiss = onDismiss, body = {
        Column(Modifier.selectableGroup()) {
            LanguageOption(stringResource(R.string.language_system_default), device?.let { Locales.nativeName(it.toLanguageTag()) },
                null, selected = system) { onPick(null) }
            for (tag in tags) {
                val native = Locales.nativeName(tag)
                val local = Locales.localName(tag, ui).takeIf { it != native }
                LanguageOption(native, local, tag, selected = !system && current == tag) { onPick(tag) }
            }
        }
    }) {
        Btn(stringResource(R.string.common_cancel), primary = false, onClick = onDismiss)
    }
}

/** One radio row. A native name is laid out in its own script direction, whatever the UI direction is. */
@Composable
private fun LanguageOption(title: String, sub: String?, tag: String?, selected: Boolean, onClick: () -> Unit) {
    val p = P
    val own = tag?.let { ComposeLocales(it) }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp))
        .background(if (selected) p.s3 else Color.Transparent)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            androidx.compose.material3.Text(title, style = sans(15.5f, 600, p.tx).copy(localeList = own, textDirection = TextDirection.Content))
            if (sub != null) androidx.compose.material3.Text(sub, style = sans(12.5f, 400, p.mu))
        }
        Spacer(Modifier.width(10.dp))
        if (selected) Icn(Ic.check, p.acct, 20.dp, Modifier.size(20.dp))
    }
}

/** Drawings with time on the x-axis (bar charts, the day strip) run right to left in RTL, like the label rows under them. */
@Composable
fun Modifier.mirrorInRtl(): Modifier = if (LocalLayoutDirection.current == LayoutDirection.Rtl) scale(-1f, 1f) else this
