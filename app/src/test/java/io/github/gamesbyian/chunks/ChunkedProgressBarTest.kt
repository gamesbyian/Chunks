package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkedProgressBarTest {
    @Test fun zeroIsEmptyAndFullIsExactWithoutImaginarySlots() {
        val zero = io.github.gamesbyian.chunks.model.ProgressChunkHistory().observe(0, 3_000)
        assertTrue(zero.paintedBands().isEmpty())
        val complete = zero.observe(1_000_000, 3_000)
        assertEquals(listOf(1_000_000), complete.paintedBands())
    }

    @Test fun oldestFirstCompressionPreservesOrderAreaAndRecentBands() {
        val bands = List(20) { 10_000 }
        val compact = coalesceOldest(bands, 8)
        assertEquals(8, compact.size)
        assertEquals(200_000, compact.sum())
        assertEquals(130_000, compact.first())
        assertEquals(List(7) { 10_000 }, compact.drop(1))
    }

    @Test fun invalidOrOutOfRangeProgressDoesNotProduceFalseCompletion() {
        assertEquals(0f, safeProgressFraction(Float.NaN), 0f)
        assertEquals(0f, safeProgressFraction(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, safeProgressFraction(-2f), 0f)
        assertEquals(1f, safeProgressFraction(2f), 0f)
    }

    @Test fun smallWindowCanHaveOneProportionalBand() {
        assertEquals(listOf(75_000), coalesceOldest(listOf(25_000, 25_000, 25_000), 1))
        assertEquals(listOf(10_000, 20_000), coalesceOldest(listOf(10_000, 20_000), 20))
    }
}
