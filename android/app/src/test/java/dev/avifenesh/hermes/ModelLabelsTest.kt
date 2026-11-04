package dev.avifenesh.hermes

import org.junit.Assert.assertEquals
import org.junit.Test

/** Short model labels stay short until two different ids in the same list would read the same. */
class ModelLabelsTest {
    @Test fun uniqueIdsKeepTheirShortLabels() {
        assertEquals(mapOf("claude-opus-5-5" to "opus 5.5", "anthropic/claude-sonnet-4-20250514" to "sonnet 4"),
            labelsFor(listOf("claude-opus-5-5", "anthropic/claude-sonnet-4-20250514")))
    }

    @Test fun bedrockRegionsAndTheDirectIdAreToldApart() {
        val l = labelsFor(listOf("global.anthropic.claude-opus-5-5", "us.anthropic.claude-opus-5-5", "claude-opus-5-5"))
        assertEquals("opus 5.5 (global)", l["global.anthropic.claude-opus-5-5"])
        assertEquals("opus 5.5 (us)", l["us.anthropic.claude-opus-5-5"])
        assertEquals("opus 5.5", l["claude-opus-5-5"])
    }

    @Test fun aSinglePrefixedCollisionNamesItsFirstSegment() {
        assertEquals(mapOf("global.anthropic.claude-opus-5-5" to "opus 5.5 (global)", "claude-opus-5-5" to "opus 5.5"),
            labelsFor(listOf("global.anthropic.claude-opus-5-5", "claude-opus-5-5")))
        assertEquals(mapOf("anthropic/claude-opus-5-5" to "opus 5.5 (anthropic)", "claude-opus-5-5" to "opus 5.5"),
            labelsFor(listOf("anthropic/claude-opus-5-5", "claude-opus-5-5")))
    }

    @Test fun datedVariantsFallBackToTheFullId() {
        assertEquals(mapOf("claude-opus-5-5" to "claude-opus-5-5", "claude-opus-5-5-20261001" to "claude-opus-5-5-20261001"),
            labelsFor(listOf("claude-opus-5-5", "claude-opus-5-5-20261001")))
        val l = labelsFor(listOf("global.anthropic.claude-opus-5-5", "global.anthropic.claude-opus-5-5-20261001", "claude-sonnet-5-5"))
        assertEquals("global.anthropic.claude-opus-5-5", l["global.anthropic.claude-opus-5-5"])
        assertEquals("global.anthropic.claude-opus-5-5-20261001", l["global.anthropic.claude-opus-5-5-20261001"])
        assertEquals("sonnet 5.5", l["claude-sonnet-5-5"])
    }

    @Test fun everyLabelInAListIsDistinct() {
        val ids = listOf("global.anthropic.claude-opus-5-5", "us.anthropic.claude-opus-5-5", "eu.anthropic.claude-opus-5-5",
            "claude-opus-5-5", "claude-opus-5-5-20261001", "anthropic/claude-opus-5-5", "openai.gpt-6.1-sol", "gpt-6.1-sol")
        val l = labelsFor(ids)
        assertEquals(ids.toSet(), l.keys)
        assertEquals(ids.size, l.values.toSet().size)
    }

    @Test fun blankAndRepeatedIdsAreHarmless() {
        assertEquals(mapOf("claude-opus-5-5" to "opus 5.5"), labelsFor(listOf("", "claude-opus-5-5", "claude-opus-5-5")))
    }

    @Test fun oneModelIsLabelledAgainstTheMachinesRecentModels() {
        assertEquals("opus 5.5 (global)", modelLabel("global.anthropic.claude-opus-5-5", listOf("claude-opus-5-5", "claude-sonnet-5-5")))
        assertEquals("opus 5.5", modelLabel("global.anthropic.claude-opus-5-5", listOf("claude-sonnet-5-5")))
        assertEquals("opus 5.5", modelLabel("claude-opus-5-5", listOf("claude-opus-5-5")))
        assertEquals("", modelLabel("", listOf("claude-opus-5-5")))
    }
}
