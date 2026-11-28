package io.github.gamesbyian.chunks.engine

import org.junit.Assert.*
import org.junit.Test

class LocalVerifiedPieceMapTest {
    @Test fun exactPositionsAndCountsAreNotChronologicalProgress() {
        val observed = requireNotNull(LocalVerifiedPieceMap.observe(4) { it == 0 || it == 2 })
        assertEquals(4, observed.totalPieces)
        assertEquals(2, observed.verifiedPieces)
        assertEquals(listOf(1, 0, 1, 0), observed.ranges.map { it.verified })
        assertEquals(listOf(0, 1, 2, 3), observed.ranges.map { it.first })
    }

    @Test fun largeInventoryAggregatesEveryBitIntoBoundedContiguousRanges() {
        var reads = 0
        val observed = requireNotNull(LocalVerifiedPieceMap.observe(65_535) {
            assertEquals(reads++, it)
            it % 3 == 0
        })
        assertEquals(65_535, reads)
        assertEquals(65_535, observed.ranges.sumOf { it.count })
        assertEquals(21_845, observed.verifiedPieces)
        assertTrue(observed.ranges.size <= LocalVerifiedPieceMap.MAX_RANGES)
        observed.ranges.zipWithNext().forEach { (a, b) -> assertEquals(a.first + a.count, b.first) }
    }

    @Test fun invalidOrOversizedRefusedBeforeNativeBitReads() {
        for (size in listOf(-1, 0, LocalVerifiedPieceMap.MAX_PIECES + 1, Int.MAX_VALUE)) {
            assertNull(LocalVerifiedPieceMap.observe(size) { error("must not read") })
        }
    }
}
