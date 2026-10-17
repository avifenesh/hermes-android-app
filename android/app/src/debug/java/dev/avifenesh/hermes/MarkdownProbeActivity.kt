package dev.avifenesh.hermes

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity

/** Debug-only rendering and real Compose-link hit-test fixture. Opens no network destination. */
class MarkdownProbeActivity : FragmentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        check(!Prefs.paired && android.os.Build.PRODUCT.contains("sdk")) { "Disposable unpaired emulator only" }
        val mode = intent.getIntExtra("theme", 1)
        val sample = """
            # Markdown links

            **[Download Hermes APK](https://example.com/download/Hermes.apk)**

            *[Italic download](https://example.com/italic)*

            [**Formatted label**](https://example.com/formatted)

            *outer **inner** end*

            **outer *inner***

            **[Nested APK](https://example.com/nested-apk) *inner***

            ## Lists and quotes

            - **Bold with [nested link](https://example.com/nested)**
            - Plain URL: https://example.com/path_(one).

            > Quote with **[quoted link](https://example.com/quote)**.

            | Type | Action |
            | --- | --- |
            | Update | **[Table download](https://example.com/table)** |

            ## Code stays literal

            `https://example.com/code`

            ```sh
            printf 'Markdown is readable'
            ```
        """.trimIndent()
        val uri = object : UriHandler {
            override fun openUri(uri: String) { Log.i("HermesMarkdownProbe", "OPEN $uri") }
        }
        setContent {
            HermesTheme(mode) {
                CompositionLocalProvider(LocalUriHandler provides uri) {
                    LazyColumn(Modifier.fillMaxSize().background(P.bg), contentPadding = PaddingValues(20.dp, 42.dp, 20.dp, 36.dp)) {
                        item { Column(Modifier.padding(4.dp)) { Md(sample) } }
                    }
                }
            }
        }
    }
}
