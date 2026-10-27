package io.github.gamesbyian.chunks.model

import org.junit.Assert.*
import org.junit.Test

class LocalInventoryFreshnessTest {
    @Test fun nativeFailureNeverSilentlyErasesPreviouslyDisplayedTransfers() {
        val known = LocalInventoryFrame(listOf("hash-1", "hash-2"), stale = false)
        val interrupted = LocalInventoryFreshness.advance(known, null)
        assertEquals(known.entries, interrupted.entries)
        assertTrue(interrupted.stale)
        assertFalse(interrupted.actionsAllowed)
        assertEquals(interrupted, LocalInventoryFreshness.advance(interrupted, null))
    }

    @Test fun confirmedEmptyInventoryMayClearStaleEntries() {
        val stale = LocalInventoryFrame(listOf("hash-1"), stale = true)
        val cleared = LocalInventoryFreshness.advance(stale, emptyList())
        assertTrue(cleared.entries.isEmpty())
        assertFalse(cleared.stale)
        assertTrue(cleared.actionsAllowed)
    }

    @Test fun freshInventoryRestoresAccurateActionsOnlyAfterSuccessfulObservation() {
        val previous = LocalInventoryFrame(emptyList<String>(), stale = true)
        val recovered = LocalInventoryFreshness.advance(previous, listOf("hash-3"))
        assertEquals(listOf("hash-3"), recovered.entries)
        assertFalse(recovered.stale)
        assertTrue(recovered.actionsAllowed)
    }
}
