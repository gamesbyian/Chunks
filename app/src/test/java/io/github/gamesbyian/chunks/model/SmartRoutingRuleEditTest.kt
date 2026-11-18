package io.github.gamesbyian.chunks.model

import org.junit.Assert.*
import org.junit.Test

class SmartRoutingRuleEditTest {
    private val original = SmartRoutingPreview.Rule(
        id = "existing-tv-rule", priority = 9, kind = SmartRoutingPreview.Kind.TV,
        minimumConfidence = 92, targetEnvironmentId = "local"
    )
    private val sibling = SmartRoutingPreview.Rule(
        id = "keep-movies", priority = 2, kind = SmartRoutingPreview.Kind.MOVIE,
        minimumConfidence = 65, targetEnvironmentId = "local"
    )

    @Test fun editKeepsStableIdentityPrecedenceAndThreshold() {
        val updated = requireNotNull(SmartRoutingRuleEdit.replace(
            listOf(original, sibling), original.id,
            SmartRoutingPreview.Kind.AUDIOBOOK, "a".repeat(64)))
        assertEquals(original.id, updated[0].id)
        assertEquals(original.priority, updated[0].priority)
        assertEquals(original.minimumConfidence, updated[0].minimumConfidence)
        assertEquals(SmartRoutingPreview.Kind.AUDIOBOOK, updated[0].kind)
        assertEquals("a".repeat(64), updated[0].targetEnvironmentId)
        assertEquals(sibling, updated[1])
    }

    @Test fun editingMissingRuleCannotAccidentallyAppendOrOverwriteAnother() {
        assertNull(SmartRoutingRuleEdit.replace(
            listOf(original, sibling), "removed-rule", SmartRoutingPreview.Kind.GAME, "local"))
        assertEquals(listOf(original, sibling), listOf(original, sibling))
    }
}
