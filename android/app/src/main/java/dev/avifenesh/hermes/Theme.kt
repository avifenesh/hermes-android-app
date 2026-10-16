@file:OptIn(ExperimentalTextApi::class)

package dev.avifenesh.hermes

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class Pal(
    val dark: Boolean,
    val bg: Color, val s1: Color, val s2: Color, val s3: Color, val s4: Color,
    val line: Color, val line2: Color,
    val tx: Color, val tx2: Color, val mu: Color, val dim: Color,
    val acc: Color, val acct: Color, val accd: Color, val ink: Color,
    val att: Color, val attd: Color, val bad: Color, val badd: Color, val good: Color, val nav: Color,
)

val Night = Pal(
    true, Color(0xFF0C0C0E), Color(0xFF141417), Color(0xFF1C1C20), Color(0xFF26262B), Color(0xFF323238),
    Color(0x12FFFFFF), Color(0x21FFFFFF), Color(0xFFF3F0EA), Color(0xFFC2BDB5), Color(0xFFAAA59B), Color(0xFFA09B93),
    Color(0xFFD4F75C), Color(0xFFD4F75C), Color(0x24D4F75C), Color(0xFF0C0C0E),
    Color(0xFFFF8A66), Color(0x24FF8A66), Color(0xFFFF5C77), Color(0x21FF5C77), Color(0xFF7BE0A6), Color(0xF01E1E22),
)
val Day = Pal(
    false, Color(0xFFF4F2ED), Color(0xFFEAE7E0), Color(0xFFE1DDD4), Color(0xFFD6D1C7), Color(0xFFC7C1B6),
    Color(0x14161513), Color(0x24161513), Color(0xFF161513), Color(0xFF45423C), Color(0xFF514B44), Color(0xFF615C54),
    Color(0xFFCDF24A), Color(0xFF4F6600), Color(0x216E8C00), Color(0xFF0C0C0E),
    Color(0xFFD2532D), Color(0x1FD2532D), Color(0xFFD2334F), Color(0x1AD2334F), Color(0xFF2E8A57), Color(0xF0FFFFFF),
)

val LocalPal = staticCompositionLocalOf { Night }
val P: Pal
    @Composable @ReadOnlyComposable get() = LocalPal.current

private fun vf(res: Int, w: Int) = Font(res, weight = FontWeight(w), style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val Sans = FontFamily(vf(R.font.geist, 400), vf(R.font.geist, 500), vf(R.font.geist, 600), vf(R.font.geist, 700))
val Mono = FontFamily(vf(R.font.geist_mono, 400), vf(R.font.geist_mono, 500), vf(R.font.geist_mono, 600))
val Serif = FontFamily(
    Font(R.font.instrument_serif, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

fun sans(size: Float, w: Int = 400, c: Color = Color.Unspecified, lh: Float = size * 1.38f) =
    TextStyle(fontFamily = Sans, fontWeight = FontWeight(w), fontSize = size.sp, lineHeight = lh.sp, color = c, textDirection = TextDirection.Content)
fun mono(size: Float, w: Int = 400, c: Color = Color.Unspecified) =
    TextStyle(fontFamily = Mono, fontWeight = FontWeight(w), fontSize = size.sp, lineHeight = (size * 1.42f).sp, color = c, textDirection = TextDirection.Content)
fun serif(size: Float, c: Color = Color.Unspecified, italic: Boolean = false) =
    TextStyle(fontFamily = Serif, fontSize = size.sp, lineHeight = (size * 1.08f).sp, color = c,
        textDirection = TextDirection.Content, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal, letterSpacing = (-0.4).sp)

/** Accent presets: (name, night accent, day text-safe accent). Lime is the design default. */
val Accents = listOf(
    Triple("Lime", Color(0xFFD4F75C), Color(0xFF4F6600)),
    Triple("Coral", Color(0xFFFF8A66), Color(0xFFB2431F)),
    Triple("Sky", Color(0xFF7CC8FF), Color(0xFF1F5F92)),
    Triple("Violet", Color(0xFFB9A2FF), Color(0xFF5B3FC4)),
    Triple("Mint", Color(0xFF7BE0A6), Color(0xFF1E7A48)),
)

fun Pal.withAccent(i: Int): Pal {
    if (i <= 0 || i >= Accents.size) return this
    val (_, night, day) = Accents[i]
    return if (dark) copy(acc = night, acct = night, accd = night.copy(alpha = 0.14f))
    else copy(acc = night, acct = day, accd = day.copy(alpha = 0.13f))
}

@Composable
fun HermesTheme(mode: Int, content: @Composable () -> Unit) {
    val dark = when (mode) { 1 -> true; 2 -> false; else -> isSystemInDarkTheme() }
    val p = (if (dark) Night else Day).withAccent(Ui.accent.intValue)
    val cs = if (dark) darkColorScheme(
        primary = p.acc, onPrimary = p.ink, secondary = p.acc, onSecondary = p.ink, background = p.bg, onBackground = p.tx,
        surface = p.s1, onSurface = p.tx, surfaceVariant = p.s2, onSurfaceVariant = p.tx2, outline = p.line2, error = p.bad,
        surfaceContainerLowest = p.bg, surfaceContainerLow = p.s1, surfaceContainer = p.s1, surfaceContainerHigh = p.s2, surfaceContainerHighest = p.s3,
    ) else lightColorScheme(
        primary = p.acct, onPrimary = Color.White, secondary = p.acct, onSecondary = Color.White, background = p.bg, onBackground = p.tx,
        surface = p.s1, onSurface = p.tx, surfaceVariant = p.s2, onSurfaceVariant = p.tx2, outline = p.line2, error = p.bad,
        surfaceContainerLowest = p.bg, surfaceContainerLow = p.s1, surfaceContainer = p.s1, surfaceContainerHigh = p.s2, surfaceContainerHighest = p.s3,
    )
    val act = LocalActivity.current as? ComponentActivity
    SideEffect {
        act?.enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
    }
    CompositionLocalProvider(LocalPal provides p) {
        MaterialTheme(colorScheme = cs, typography = Typography(bodyLarge = sans(16f), bodyMedium = sans(14f), labelLarge = sans(14f, 600)), content = content)
    }
}

object Ic {
    private fun v(d: String, w: Float = 1.8f, fill: Boolean = false, autoMirror: Boolean = false): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f, autoMirror = autoMirror).apply {
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = if (fill) SolidColor(Color.Black) else null,
                stroke = if (fill) null else SolidColor(Color.Black),
                strokeLineWidth = w, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    val mark by lazy { v("M7 5v14M17 5v14M7 14.6L17 9.4", 2.6f) }
    val inbox by lazy { v("M22 12h-6l-2 3h-4l-2-3H2M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z") }
    val pulse by lazy { v("M22 12h-4l-3 9L9 3l-3 9H2") }
    val cron by lazy { v("M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20zM12 6v6l4 2") }
    val me by lazy { v("M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M12 3a4 4 0 1 0 0 8a4 4 0 1 0 0-8z") }
    val pen by lazy { v("M12 20h9M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1l1-4z", 2f) }
    val send by lazy { v("M12 19V5M5 12l7-7l7 7", 2.2f) }
    val stop by lazy { v("M6 6h12v12H6z", fill = true) }
    val play by lazy { v("M7 4.5l12 7.5l-12 7.5z", fill = true) }
    val mic by lazy { v("M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3zM19 10v2a7 7 0 0 1-14 0v-2M12 19v3") }
    val plus by lazy { v("M12 5v14M5 12h14", 2f) }
    val camera by lazy { v("M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2zM12 9a4 4 0 1 0 0 8a4 4 0 1 0 0-8z") }
    // Only navigation directions mirror; charts, media and other pictograms keep their geometry.
    val back by lazy { v("M15 18l-6-6l6-6", 2f, autoMirror = true) }
    val chevR by lazy { v("M9 18l6-6l-6-6", autoMirror = true) }
    val chevD by lazy { v("M6 9l6 6l6-6") }
    val chevU by lazy { v("M18 15l-6-6l-6 6") }
    val search by lazy { v("M11 3a8 8 0 1 0 0 16a8 8 0 1 0 0-16zM21 21l-4.35-4.35") }
    val tool by lazy { v("M4 17l6-6l-6-6M12 19h8") }
    val check by lazy { v("M20 6L9 17l-5-5", 2f) }
    val x by lazy { v("M18 6L6 18M6 6l12 12", 2f) }
    val file by lazy { v("M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6") }
    val link by lazy { v("M10 13a5 5 0 0 0 7.54 0.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71M14 11a5 5 0 0 0-7.54-0.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71") }
    val digest by lazy { v("M4 22h16a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2H8a2 2 0 0 0-2 2v16a2 2 0 0 1-2 2zM4 22a2 2 0 0 1-2-2v-9c0-1.1 0.9-2 2-2h2M18 14h-8M15 18h-5M10 6h8v4h-8z") }
    val shield by lazy { v("M12 22s8-4 8-10V5l-8-3l-8 3v7c0 6 8 10 8 10z") }
    val bell by lazy { v("M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.73 21a2 2 0 0 1-3.46 0") }
    val phone by lazy { v("M17 2H7a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2zM12 18h0.01") }
    val server by lazy { v("M4 2h16v8H4zM4 14h16v8H4zM6 6h0.01M6 18h0.01") }
    val refresh by lazy { v("M23 4v6h-6M1 20v-6h6M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15") }
    val qr by lazy { v("M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h3v3h-3zM18 18h3v3h-3zM18 14h3M14 18v3") }
    val sliders by lazy { v("M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6") }
    val blocks by lazy { v("M10 22V7a1 1 0 0 0-1-1H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-5a1 1 0 0 0-1-1H2M14 2h6a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2h-6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z") }
    val book by lazy { v("M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2zM22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z") }
    val cpu by lazy { v("M4 4h16v16H4zM9 9h6v6H9zM9 1v3M15 1v3M9 20v3M15 20v3M20 9h3M20 14h3M1 9h3M1 14h3") }
    val zap by lazy { v("M13 2L3 14h9l-1 8l10-12h-9l1-8z") }
    val power by lazy { v("M18.36 6.64a9 9 0 1 1-12.73 0M12 2v10", 2f) }
    val download by lazy { v("M12 3v12M7 10l5 5l5-5M5 21h14") }
    val more by lazy { v("M12 5h0.01M12 12h0.01M12 19h0.01", 3f) }
    val trash by lazy { v("M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6") }
    val edit by lazy { v("M17 3a2.83 2.83 0 1 1 4 4L7.5 20.5L2 22l1.5-5.5z") }
    val chart by lazy { v("M3 3v18h18M7 16v-5M12 16V8M17 16v-8") }
    val pin by lazy { v("M12 17v5M9 10.76V6h6v4.76a2 2 0 0 0 1.11 1.79l1.78 0.9A2 2 0 0 1 19 15.24V17H5v-1.76a2 2 0 0 1 1.11-1.79l1.78-0.9A2 2 0 0 0 9 10.76zM8 2h8") }
}
