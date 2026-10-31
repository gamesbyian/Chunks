package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentViewScope
import io.github.gamesbyian.chunks.model.UnifiedSortDirection
import io.github.gamesbyian.chunks.model.UnifiedSortKey
import io.github.gamesbyian.chunks.model.UnifiedTransferFilter
import io.github.gamesbyian.chunks.model.UnifiedTransferQuery
import org.junit.Assert.*
import org.junit.Test

/** A purely view-level recovery can never retarget an engine mutation or Add. */
class CombinedViewRecoveryTest {
    @Test fun searchFilterAndNarrowedEnvironmentCanBeClearedTogether() {
        val selected = EnvironmentId("a".repeat(64))
        val original = UnifiedTransferQuery(
            scope = EnvironmentViewScope.Only(setOf(selected)),
            search = "not found", filter = UnifiedTransferFilter.ATTENTION,
            sortKey = UnifiedSortKey.SIZE, direction = UnifiedSortDirection.DESCENDING,
            groupByEnvironment = true, environmentOrder = listOf(selected))
        assertTrue(CombinedViewPreferences.narrowingActive(original))
        val restored = CombinedViewPreferences.showAll(original)
        assertFalse(CombinedViewPreferences.narrowingActive(restored))
        assertTrue(restored.scope is EnvironmentViewScope.All)
        assertEquals("", restored.search)
        assertEquals(UnifiedTransferFilter.ALL, restored.filter)
        assertEquals(original.sortKey, restored.sortKey)
        assertEquals(original.direction, restored.direction)
        assertEquals(original.groupByEnvironment, restored.groupByEnvironment)
        assertEquals(original.environmentOrder, restored.environmentOrder)
        assertEquals(original, original.copy())
    }

    @Test fun defaultViewHasNoRecoveryAndExistingSortIsNotDiscarded() {
        val sorted = UnifiedTransferQuery(sortKey = UnifiedSortKey.DOWNLOAD_SPEED,
            direction = UnifiedSortDirection.ASCENDING)
        assertFalse(CombinedViewPreferences.narrowingActive(sorted))
        assertEquals(sorted, CombinedViewPreferences.showAll(sorted))
        assertTrue(CombinedViewPreferences.narrowingActive(sorted.copy(search = "   ")))
        assertEquals(sorted, CombinedViewPreferences.showAll(sorted.copy(search = "   ")))
    }
}
