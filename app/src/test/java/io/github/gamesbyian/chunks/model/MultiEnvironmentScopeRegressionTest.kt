package io.github.gamesbyian.chunks.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Independent G2 contract tests: the visible union is never an authority to
 * change the selected environment or collapse matching hashes across profiles.
 */
class MultiEnvironmentScopeRegressionTest {
    private val phone = EnvironmentSnapshots.PHONE
    private val first = EnvironmentId("remote-first")
    private val second = EnvironmentId("remote-second")
    private val sharedHash = "a".repeat(40)

    private fun transfer(environment: EnvironmentId, name: String, bytes: Long?) =
        UnifiedTransferObservation(
            key = UnifiedTransferKey.of(environment, sharedHash),
            displayName = name, originalName = name,
            sizeBytes = bytes, progressPpm = null, started = null,
            downloadBytesPerSecond = null, uploadBytesPerSecond = null, peers = null
        )

    private fun source(environment: EnvironmentId, row: UnifiedTransferObservation,
                       availability: EnvironmentAvailability = EnvironmentAvailability.LIVE) =
        EnvironmentTransferSnapshot(
            environment = environment, availability = availability,
            inventoryComplete = availability == EnvironmentAvailability.LIVE,
            observedAtEpochMillis = 1L, transfers = listOf(row)
        )

    @Test fun everySubsetRetainsExactProfileIdentityEvenWithIdenticalHashes() {
        val snapshots = listOf(
            source(phone, transfer(phone, "Shared", 30)),
            source(first, transfer(first, "Shared", 10)),
            source(second, transfer(second, "Shared", 20))
        )
        val allIds = listOf(phone, first, second)
        for (mask in 1 until (1 shl allIds.size)) {
            val selected = allIds.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            for (group in listOf(false, true)) {
                for (direction in UnifiedSortDirection.entries) {
                    val result = UnifiedTransferCatalog.query(snapshots, UnifiedTransferQuery(
                        scope = EnvironmentViewScope.Only(selected),
                        sortKey = UnifiedSortKey.SIZE, direction = direction,
                        groupByEnvironment = group
                    ))
                    assertEquals(selected, result.sources.map { it.environment }.toSet())
                    assertEquals(selected, result.transfers.map { it.key.environment }.toSet())
                    assertEquals(selected.size, result.transfers.map { it.key }.toSet().size)
                    assertTrue(result.transfers.all { it.key.infoHash == sharedHash })
                    assertTrue(result.missingSelectedEnvironments.isEmpty())
                    assertFalse(result.hasIncompleteOrStaleSource)
                }
            }
        }
    }

    @Test fun missingProfilesStayMissingEvenWhenTheSameHashExistsElsewhere() {
        val snapshots = listOf(source(phone, transfer(phone, "Same", 10)))
        val result = UnifiedTransferCatalog.query(snapshots, UnifiedTransferQuery(
            scope = EnvironmentViewScope.Only(setOf(first, phone, second)),
            search = sharedHash, groupByEnvironment = true
        ))
        assertEquals(setOf(first, second), result.missingSelectedEnvironments)
        assertEquals(listOf(phone), result.transfers.map { it.key.environment })
        assertTrue(result.hasIncompleteOrStaleSource)
    }

    @Test fun staleAndOfflineProfilesRemainDisclosedWhenFiltersHideTheirRows() {
        val snapshots = listOf(
            source(phone, transfer(phone, "Phone", 10)),
            source(first, transfer(first, "Remote", null), EnvironmentAvailability.STALE),
            source(second, transfer(second, "Remote", null), EnvironmentAvailability.OFFLINE)
        )
        val result = UnifiedTransferCatalog.query(snapshots, UnifiedTransferQuery(
            search = "No matching transfer", filter = UnifiedTransferFilter.COMPLETE,
            groupByEnvironment = true
        ))
        assertTrue(result.transfers.isEmpty())
        assertEquals(setOf(phone, first, second), result.sources.map { it.environment }.toSet())
        assertEquals(3, result.groups.size)
        assertTrue(result.groups.all { it.transfers.isEmpty() })
        assertTrue(result.hasIncompleteOrStaleSource)
    }

    @Test fun unknownValuesRemainLastWithinEveryGroupInBothDirections() {
        val unknownHash = "b".repeat(40)
        val sources = listOf(phone, first, second).map { environment ->
            val known = transfer(environment, "Known", 15)
            val unknown = transfer(environment, "Unknown", null)
                .copy(key = UnifiedTransferKey.of(environment, unknownHash))
            EnvironmentTransferSnapshot(environment, EnvironmentAvailability.LIVE,
                true, 1L, listOf(unknown, known))
        }
        for (direction in UnifiedSortDirection.entries) {
            val result = UnifiedTransferCatalog.query(sources, UnifiedTransferQuery(
                groupByEnvironment = true, sortKey = UnifiedSortKey.SIZE, direction = direction
            ))
            assertTrue(result.groups.all { group ->
                group.transfers.map { it.sizeBytes } == listOf(15L, null)
            })
        }
    }
}
