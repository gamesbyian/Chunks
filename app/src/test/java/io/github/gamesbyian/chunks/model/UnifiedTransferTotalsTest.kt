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

    @Test fun aMissingSavedEnvironmentIsCountedAsNotIncluded() {
        val result = UnifiedTransferCatalog.query(emptyList(),
            UnifiedTransferQuery(scope = EnvironmentViewScope.Only(setOf(live))))
        assertEquals(1, UnifiedTransferTotals.of(result).excludedEnvironments)
        assertEquals(0, UnifiedTransferTotals.of(result).liveRows)
    }
}
