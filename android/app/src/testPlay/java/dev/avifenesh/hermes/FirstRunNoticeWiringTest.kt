package dev.avifenesh.hermes

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AI-content notice only protects users if the paired app shell shows it. Main() hosts the inbox and every
 * chat route, so the call must be in Main() itself, not behind a route or tab; Me keeps it reachable.
 */
class FirstRunNoticeWiringTest {
    private fun body(file: String, fn: String): String {
        val src = File("src/main/java/dev/avifenesh/hermes/$file").readText()
        val start = src.indexOf("fun $fn(")
        assertTrue("$fn() not found in $file", start >= 0)
        var i = src.indexOf('{', start)
        var depth = 0
        val from = i
        while (i < src.length) {
            when (src[i]) { '{' -> depth++; '}' -> if (--depth == 0) return src.substring(from, i + 1) }
            i++
        }
        error("unbalanced $fn()")
    }

    @Test fun mainShowsTheNoticeBeforeAnyChat() {
        val main = body("Ui.kt", "Main")
        assertTrue("Main() hosts the inbox and chat routes", main.contains("InboxScreen()") && main.contains("ChatScreen("))
        assertTrue("Main() must call EditionFirstRunNotice()", main.lines().any { it.trim() == "EditionFirstRunNotice()" })
    }

    @Test fun meKeepsTheNoticeReachable() {
        assertTrue(body("Screens2.kt", "MeScreen").contains("editionHelpItems()"))
    }

    @Test fun pairingStartsNoBackgroundConnection() {
        val pairing = File("src/main/java/dev/avifenesh/hermes/Pairing.kt").readText()
        assertTrue("the pairing path hands the choice to the edition", pairing.contains("LiveService.firstPair()"))
    }
}
