package dev.avifenesh.hermes

import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ceil

/**
 * Real platform line breaking. A wrap-content Text is sized to ceil(max intrinsic width), the width it draws; the
 * platform line breaker must then fit the same text on one line. With -0.4sp tracking it measures an Arabic word 1-2 px
 * wider than it draws ("الوار/د"), and an RTL line where a hyphen joins Hebrew to an unisolated Latin name likewise
 * ("הודעה ל-Hermes / Demo").
 */
class TitleWrapTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val density = Density(ctx)
    private val fonts = createFontFamilyResolver(ctx)

    private fun linesAtDrawnWidth(text: String, style: TextStyle): Int {
        val intrinsics = ParagraphIntrinsics(text = text, style = style, annotations = emptyList(), density = density, fontFamilyResolver = fonts)
        return Paragraph(intrinsics, Constraints(maxWidth = ceil(intrinsics.maxIntrinsicWidth).toInt())).lineCount
    }

    @Test fun titlesAndTheClockFitOnOneLineInEveryScript() {
        val titles = listOf("الوارد", "أنا", "النبض", "المنبّه", "١١:٢١ م", "Inbox", "Me", "הודעות", "שעון מעורר", "受信トレイ", "マイページ", "Eingang")
        for (size in listOf(24f, 36f, 40f, 46f, 64f)) for (t in titles) {
            assertEquals("'$t' at ${size}sp", 1, linesAtDrawnWidth(t, serif(size).trackFor(t)))
        }
    }

    @Test fun anIsolatedNameKeepsAnRtlHintOnOneLine() {
        for (t in listOf("הודעה ל-" + bidiIsolate("Hermes Demo"), "رسالة إلى " + bidiIsolate("Hermes Demo"))) {
            assertEquals(t, 1, linesAtDrawnWidth(t, sans(16f, 400, lh = 16f * 1.42f)))
        }
    }
}
