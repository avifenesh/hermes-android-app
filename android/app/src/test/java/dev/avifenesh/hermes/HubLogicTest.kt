package dev.avifenesh.hermes

import org.junit.Assert.assertEquals
import org.junit.Test

class HubLogicTest {
    @Test fun guardKeyMatchesServer() {
        assertEquals("skill:install:official/dogfood/adversarial-ux-test", HubLogic.guardKey("skill", "install", "official/dogfood/adversarial-ux-test"))
        assertEquals("plugin:update:x:accept", HubLogic.guardKey("plugin", "update", "x", accept = true))
    }

    @Test fun onlyAllowInstalls() {
        assertEquals(HubLogic.Gate.ALLOW, HubLogic.gate("allow", false))
        assertEquals(HubLogic.Gate.REFUSED, HubLogic.gate("ask", false))
        assertEquals(HubLogic.Gate.REFUSED, HubLogic.gate("block", false))
        assertEquals(HubLogic.Gate.SCANNING, HubLogic.gate(null, false))
        assertEquals(HubLogic.Gate.SCAN_FAILED, HubLogic.gate("allow", true))
    }

    @Test fun findings() {
        assertEquals("no findings", HubLogic.findingsLine(mapOf("low" to 0)))
        assertEquals("1 critical · 3 low", HubLogic.findingsLine(mapOf("low" to 3, "critical" to 1, "high" to 0)))
    }

    @Test fun profileParam() {
        assertEquals("/v1/hub/skills/browse", HubLogic.withProfile("/v1/hub/skills/browse", ""))
        assertEquals("/v1/hub/skills/browse?profile=seller", HubLogic.withProfile("/v1/hub/skills/browse", "seller"))
        assertEquals("/v1/hub/skills/search?q=pdf&profile=a+b", HubLogic.withProfile("/v1/hub/skills/search?q=pdf", "a b"))
    }

    @Test fun env() {
        val req = listOf("A" to true, "B" to false, "C" to true)
        assertEquals(listOf("C"), HubLogic.missingEnv(req, mapOf("A" to "x", "C" to " ")))
        assertEquals(emptyList<String>(), HubLogic.missingEnv(req, mapOf("A" to "x"), present = setOf("C")))
        assertEquals(listOf("A", "C"), HubLogic.missingEnv(req, emptyMap()))
    }

    @Test fun shelvesBySize() {
        data class S(val c: String, val n: String)
        val s = HubLogic.shelves(listOf(S("b", "z"), S("a", "y"), S("b", "a"), S("", "q")), { it.c }, { it.n })
        assertEquals(listOf("b", "a", "general"), s.map { it.first })
        assertEquals(listOf("a", "z"), s[0].second.map { it.n })
    }

    @Test fun discoveryFiltersComposeWithoutAQuery() {
        val discover = HubLogic.Filters()
        assertEquals(false, discover.matches(true, "official", "research", ""))
        assertEquals(true, discover.matches(false, "official", "research", ""))
        val filtered = HubLogic.Filters(source = "github", category = "research")
        assertEquals(false, filtered.matches(false, "official", "research", ""))
        assertEquals(false, filtered.matches(false, "github", "coding", ""))
        assertEquals(true, filtered.matches(false, "github", "research", ""))
    }

    @Test fun installedAndAllKeepOtherFilters() {
        val installed = HubLogic.Filters(scope = "installed", query = "PDF", category = "docs")
        assertEquals(true, installed.matches(true, "official", "docs", "Read pdf files"))
        assertEquals(false, installed.matches(false, "official", "docs", "Read pdf files"))
        assertEquals(false, installed.matches(true, "official", "coding", "Read pdf files"))
        assertEquals(false, installed.matches(true, "official", "docs", "Read images"))
        val all = HubLogic.Filters(scope = "all", query = "  PDF  ")
        assertEquals(true, all.matches(true, "github", "docs", "pdf"))
        assertEquals(true, all.matches(false, "github", "docs", "pdf"))
    }

    @Test fun tail() {
        assertEquals("c\nd", HubLogic.logTail("a\nb\nc\nd\n\n", 2))
    }
}
