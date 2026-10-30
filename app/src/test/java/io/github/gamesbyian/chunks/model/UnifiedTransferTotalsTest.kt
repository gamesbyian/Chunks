package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.combinedTotalsText
import org.junit.Assert.*
import org.junit.Test

class UnifiedTransferTotalsTest {
    private val phone = EnvironmentId("phone")
    private val live = EnvironmentId("a".repeat(64))
    private val stale = EnvironmentId("b".repeat(64))

    private fun row(env: EnvironmentId, c: Char, down: Long?, up: Long?) = UnifiedTransferObservation(
        UnifiedTransferKey.of(env, c.toString().repeat(40)), "n", "n", 1, 0, true, down, up, null)

    @Test fun onlyLiveRowsCountAndUnknownRatesAreNotZero() {
        val sources = listOf(
            EnvironmentTransferSnapshot(phone, EnvironmentAvailability.LIVE, true, 1,
                listOf(row(phone, '1', 1000, 10), row(phone, '2', null, null))),
            EnvironmentTransferSnapshot(live, EnvironmentAvailability.LIVE, true, 1, listOf(row(live, '3', 24, 0))),
            EnvironmentTransferSnapshot(stale, EnvironmentAvailability.STALE, true, 1, listOf(row(stale, '4', 999_999, 5))))
        val result = UnifiedTransferCatalog.query(sources, UnifiedTransferQuery())
        val totals = UnifiedTransferTotals.of(result)
        assertEquals(3, totals.liveRows)
        assertEquals(1024, totals.downloadBytesPerSecond)
        assertEquals(10, totals.uploadBytesPerSecond)
        assertEquals(1, totals.rowsWithUnknownRate)
        assertEquals(1, totals.excludedEnvironments)
        assertEquals("Live total: ↓ 1.0 KiB/s · ↑ 10 B/s across 3 transfers · 1 with rates not reported · " +
            "1 environment(s) excluded (not fully observed)", combinedTotalsText(totals))
    }

    @Test fun incompleteOrUnobservedLiveInventoriesCannotInflateAggregateRates() {
        val partial = EnvironmentId("c".repeat(64))
        val noTimestamp = EnvironmentId("d".repeat(64))
        val sources = listOf(
            EnvironmentTransferSnapshot(phone, EnvironmentAvailability.LIVE, true, 12,
                listOf(row(phone, '1', 64, 8))),
            EnvironmentTransferSnapshot(partial, EnvironmentAvailability.LIVE, false, 12,
                listOf(row(partial, '2', 400_000, 300_000))),
            EnvironmentTransferSnapshot(noTimestamp, EnvironmentAvailability.LIVE, true, null,
                listOf(row(noTimestamp, '3', 500_000, 400_000))),
            EnvironmentTransferSnapshot(stale, EnvironmentAvailability.STALE, true, 10,
                listOf(row(stale, '4', 999_999, 999_999)))
        )
        val result = UnifiedTransferCatalog.query(sources, UnifiedTransferQuery())
        assertEquals(4, result.transfers.size)
        assertTrue(result.hasIncompleteOrStaleSource)

        val totals = UnifiedTransferTotals.of(result)
        assertEquals(1, totals.liveRows)
        assertEquals(64, totals.downloadBytesPerSecond)
        assertEquals(8, totals.uploadBytesPerSecond)
        assertEquals(3, totals.excludedEnvironments)
        assertEquals(0, totals.rowsWithUnknownRate)
        assertTrue(combinedTotalsText(totals).contains("3 environment(s) excluded (not fully observed)"))
        assertEquals("Live, list may be incomplete",
            io.github.gamesbyian.chunks.availabilityText(sources[2]))
    }

    @Test fun hugeValidRatesCannotWrapToZeroOrClaimExactTotal() {
        val result = UnifiedTransferCatalog.query(listOf(
            EnvironmentTransferSnapshot(phone, EnvironmentAvailability.LIVE, true, 1L, listOf(
                row(phone, '1', Long.MAX_VALUE, Long.MAX_VALUE),
                row(phone, '2', 1L, 1L)
            ))
        ), UnifiedTransferQuery())
        val totals = UnifiedTransferTotals.of(result)
        assertEquals(Long.MAX_VALUE, totals.downloadBytesPerSecond)
        assertEquals(Long.MAX_VALUE, totals.uploadBytesPerSecond)
        assertTrue(totals.downloadTotalBeyondRange)
        assertTrue(totals.uploadTotalBeyondRange)
        assertEquals(
            "Live total: ↓ exceeds reporting range · ↑ exceeds reporting range across 2 transfers",
            combinedTotalsText(totals)
        )
    }

    @Test fun exactLongMaximumIsStillAReportedKnownRate() {
        val result = UnifiedTransferCatalog.query(listOf(
            EnvironmentTransferSnapshot(phone, EnvironmentAvailability.LIVE, true, 1L,
                listOf(row(phone, '3', Long.MAX_VALUE, 0L)))
        ), UnifiedTransferQuery())
        val totals = UnifiedTransferTotals.of(result)
        assertEquals(Long.MAX_VALUE, totals.downloadBytesPerSecond)
        assertFalse(totals.downloadTotalBeyondRange)
        assertEquals(0L, totals.uploadBytesPerSecond)
        assertFalse(totals.uploadTotalBeyondRange)
        assertTrue(combinedTotalsText(totals).contains("↑ 0 B/s"))
    }

    @Test fun aMissingSavedEnvironmentIsCountedAsNotIncluded() {
        val result = UnifiedTransferCatalog.query(emptyList(),
            UnifiedTransferQuery(scope = EnvironmentViewScope.Only(setOf(live))))
        assertEquals(1, UnifiedTransferTotals.of(result).excludedEnvironments)
        assertEquals(0, UnifiedTransferTotals.of(result).liveRows)
    }
}
