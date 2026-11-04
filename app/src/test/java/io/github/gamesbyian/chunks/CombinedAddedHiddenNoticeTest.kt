package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import io.github.gamesbyian.chunks.model.UnifiedTransferCatalog
import io.github.gamesbyian.chunks.model.UnifiedTransferQuery
import io.github.gamesbyian.chunks.model.UnifiedTransferFilter
import io.github.gamesbyian.chunks.model.EnvironmentViewScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedAddedHiddenNoticeTest {
    private val server = EnvironmentId("server-two")
    private val other = EnvironmentId("server-three")
    private val key = UnifiedTransferKey.of(server, "a".repeat(40))
    private fun source(id: EnvironmentId = server,
        availability: EnvironmentAvailability = EnvironmentAvailability.LIVE,
        complete: Boolean = true, observed: Long? = 100L) = EnvironmentTransferSnapshot(
        id, availability, complete, observed, listOf(UnifiedTransferObservation(
            UnifiedTransferKey.of(id, key.infoHash), "A transfer", "A transfer", 100,
            0, false, 0, 0, 0)))

    @Test fun exactObservedAddHiddenByTheViewNeedsANotice() {
        assertTrue(combinedAddedTransferHidden(key, listOf(source()), emptyList()))
        assertFalse(combinedAddedTransferHidden(null, listOf(source()), emptyList()))
        assertFalse(combinedAddedTransferHidden(key, emptyList(), emptyList()))
        assertFalse(combinedAddedTransferHidden(key, listOf(source()), listOf(key)))
    }

    @Test fun sameHashOnAnotherEnvironmentNeverProvesThisAdd() {
        assertFalse(combinedAddedTransferHidden(key, listOf(source(other)), emptyList()))
        assertTrue(combinedAddedTransferHidden(key, listOf(source(), source(other)),
            listOf(UnifiedTransferKey.of(other, key.infoHash))))
    }

    @Test fun lockedOfflineStaleAndUnconfirmedInventoriesCannotClaimAdded() {
        EnvironmentAvailability.entries.filter { it != EnvironmentAvailability.LIVE }.forEach {
            assertFalse(it.name, combinedAddedTransferHidden(key, listOf(source(availability = it)), emptyList()))
        }
        assertFalse(combinedAddedTransferHidden(key, listOf(source(complete = false)), emptyList()))
        assertFalse(combinedAddedTransferHidden(key, listOf(source(observed = null)), emptyList()))
    }

    @Test fun searchFilterAndEnvironmentSelectionAllHaveTheSameViewOnlyRecovery() {
        val sources = listOf(source(), source(other))
        listOf(
            UnifiedTransferQuery(search = "not found"),
            UnifiedTransferQuery(filter = UnifiedTransferFilter.STARTED),
            UnifiedTransferQuery(scope = EnvironmentViewScope.Only(setOf(other)))
        ).forEach { query ->
            val shown = UnifiedTransferCatalog.query(sources, query).transfers.map { it.key }
            assertTrue(combinedAddedTransferHidden(key, sources, shown))
            val recovered = UnifiedTransferCatalog.query(sources, CombinedViewPreferences.showAll(query))
            assertFalse(combinedAddedTransferHidden(key, sources, recovered.transfers.map { it.key }))
        }
        val differentHash = UnifiedTransferKey.of(server, "b".repeat(40))
        assertFalse("a matching title cannot prove the added hash",
            combinedAddedTransferHidden(differentHash, sources, emptyList()))
    }
}
