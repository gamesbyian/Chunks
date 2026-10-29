package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.*
import org.junit.Test

class UnifiedTransferCatalogTest {
    private val phone = EnvironmentId("phone")
    private val server1 = EnvironmentId("seedhost-1")
    private val server2 = EnvironmentId("seedhost-2")
    private fun hash(letter: Char) = letter.toString().repeat(40)
    private fun item(env: EnvironmentId, h: Char, name: String, value: Long? = null) =
        UnifiedTransferObservation(
            UnifiedTransferKey.of(env, hash(h)), name, name, value,
            value?.toInt(), true, value, value, value?.toInt(),
            addedAtEpochMillis = value
        )
    private fun snapshot(env: EnvironmentId, vararg items: UnifiedTransferObservation,
                         availability: EnvironmentAvailability = EnvironmentAvailability.LIVE,
                         complete: Boolean = true) =
        EnvironmentTransferSnapshot(env, availability, complete, 100L, items.toList())

    @Test fun sameHashAcrossEnvironmentsIsDistinctAndNeverDeduplicated() {
        val local = LocalTransferStatus(
            hash('a'), "Episode", LocalTransferState.DOWNLOADING, false,
            250_000, 1024, 256, 256, 2, 1, "/private", null
        )
        val remote = RemoteTransfer(hash('a').uppercase(), "Episode", 1024, 256, true, "/server",
            downBytesPerSecond = null)
        val a = UnifiedTransferObservations.fromPhone(phone, local)
        val b = UnifiedTransferObservations.fromRTorrent(server1, remote)
        val result = UnifiedTransferCatalog.query(listOf(snapshot(phone, a),
            snapshot(server1, b)), UnifiedTransferQuery())
        assertEquals(2, result.transfers.size)
        assertEquals(a.key.infoHash, b.key.infoHash)
        assertNotEquals(a.key, b.key)
        assertNull(b.downloadBytesPerSecond)
        assertEquals(0L, a.downloadBytesPerSecond)
        assertFalse(result.hasIncompleteOrStaleSource)
    }

    @Test fun allNumericKeysReverseAndKeepUnknownLastInBothDirections() {
        val first = item(phone, 'a', "Alpha", 10L)
        val second = item(phone, 'b', "Beta", 20L)
        val unknown = item(phone, 'c', "Unknown")
        val source = snapshot(phone, second, unknown, first)
        for (key in UnifiedSortKey.entries.filter { it != UnifiedSortKey.NAME }) {
            val asc = UnifiedTransferCatalog.query(listOf(source),
                UnifiedTransferQuery(sortKey = key, direction = UnifiedSortDirection.ASCENDING))
            val desc = UnifiedTransferCatalog.query(listOf(source),
                UnifiedTransferQuery(sortKey = key, direction = UnifiedSortDirection.DESCENDING))
            assertEquals("Ascending $key", listOf(first, second, unknown), asc.transfers)
            assertEquals("Descending $key", listOf(second, first, unknown), desc.transfers)
        }
        assertEquals(listOf(first, second, unknown), UnifiedTransferCatalog.query(listOf(source),
            UnifiedTransferQuery(sortKey = UnifiedSortKey.NAME)).transfers)
        assertEquals(listOf(unknown, second, first), UnifiedTransferCatalog.query(listOf(source),
            UnifiedTransferQuery(sortKey = UnifiedSortKey.NAME,
                direction = UnifiedSortDirection.DESCENDING)).transfers)
    }

    @Test fun groupedAndUngroupedApplySameComparatorAndPreserveSelection() {
        val a = item(phone, 'a', "Zebra", 30L)
        val b = item(phone, 'b', "Apple", 10L)
        val c = item(server1, 'c', "Beta", 40L)
        val d = item(server1, 'd', "Cherry", 20L)
        val sources = listOf(snapshot(phone, a, b), snapshot(server1, c, d))
        val sort = UnifiedTransferQuery(sortKey = UnifiedSortKey.SIZE,
            direction = UnifiedSortDirection.DESCENDING)
        assertEquals(listOf(c, a, d, b), UnifiedTransferCatalog.query(sources, sort).transfers)
        val grouped = UnifiedTransferCatalog.query(sources, sort.copy(groupByEnvironment = true,
            environmentOrder = listOf(server1, phone)))
        assertEquals(listOf(c, d, a, b), grouped.transfers)
        assertEquals(listOf(server1, phone), grouped.groups.map { it.source.environment })
        val reverse = UnifiedTransferCatalog.query(sources, sort.copy(groupByEnvironment = true,
            direction = UnifiedSortDirection.ASCENDING))
        assertEquals(listOf(b, a, d, c), reverse.transfers)
    }

    @Test fun selectedSubsetDoesNotSilentlyRetargetMissingOrLockedSource() {
        val stale = snapshot(server1, item(server1, 'a', "Stale"),
            availability = EnvironmentAvailability.OFFLINE)
        val scope = EnvironmentViewScope.Only(setOf(server1, server2))
        val result = UnifiedTransferCatalog.query(listOf(snapshot(phone), stale),
            UnifiedTransferQuery(scope = scope))
        assertEquals(listOf(server1), result.sources.map { it.environment })
        assertEquals(setOf(server2), result.missingSelectedEnvironments)
        assertTrue(result.hasIncompleteOrStaleSource)
        val all = UnifiedTransferCatalog.query(listOf(snapshot(phone), stale),
            UnifiedTransferQuery(scope = EnvironmentViewScope.All))
        assertEquals(2, all.sources.size)
    }

    @Test fun absentMetadataDoesNotBecomeCompleteOrZeroSizeEvidence() {
        val magnet = LocalTransferStatus(
            hash('b'), "Pending", LocalTransferState.DOWNLOADING_METADATA, true,
            0, 0, 0, 0, 0, 0, "/private", null, metadataKnown = false
        )
        val a = UnifiedTransferObservations.fromPhone(phone, magnet)
        assertNull(a.sizeBytes)
        assertNull(a.progressPpm)
        val result = UnifiedTransferCatalog.query(listOf(snapshot(phone, a)),
            UnifiedTransferQuery(filter = UnifiedTransferFilter.COMPLETE))
        assertTrue(result.transfers.isEmpty())
    }

    @Test fun sameEnvironmentDuplicatesOrCrossScopeRowsAreRefused() {
        val a = item(phone, 'a', "One")
        try {
            snapshot(phone, a, a.copy(key = UnifiedTransferKey.of(phone, hash('a').uppercase())))
            fail("Expected duplicate identity refusal")
        } catch (_: IllegalArgumentException) {}
        try {
            snapshot(server1, a)
            fail("Expected cross-scope refusal")
        } catch (_: IllegalArgumentException) {}
        try {
            UnifiedTransferCatalog.query(listOf(snapshot(phone), snapshot(phone)),
                UnifiedTransferQuery())
            fail("Expected duplicate environment refusal")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun filteringIsOverSelectedUnionAndDoesNotModifySnapshots() {
        val running = item(phone, 'a', "Run", 4L)
        val stopped = item(server1, 'b', "Stop", 5L).copy(started = false)
        val source1 = snapshot(phone, running)
        val source2 = snapshot(server1, stopped)
        val result = UnifiedTransferCatalog.query(listOf(source1, source2),
            UnifiedTransferQuery(filter = UnifiedTransferFilter.STOPPED,
                search = "stop", groupByEnvironment = true))
        assertEquals(listOf(stopped), result.transfers)
        assertEquals(2, result.groups.size)
        assertEquals(1, source1.transfers.size)
        assertEquals(1, source2.transfers.size)
    }
}
