package io.github.gamesbyian.chunks.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressChunkHistoryTest {
    @Test fun zeroEightUnchangedThirteenTwentyEightMakesThreeProportionalBands() {
        val zero = ProgressChunkHistory().observe(0, 1_000)
        val eight = zero.observe(80_000, 1_000)
        assertSame(eight, eight.observe(80_000, 1_000))
        val final = eight.observe(130_000, 1_000).observe(280_000, 1_000)
        assertEquals(listOf(80_000, 50_000, 150_000), final.paintedBands())
        assertEquals(280_000, final.paintedBands().sum())
    }

    @Test fun firstRemoteSyncAtSixtyPercentIsOneBaselineNotFabricatedHistory() {
        val fromCold = ProgressChunkHistory().observe(600_000, 1_000)
        assertEquals(listOf(600_000), fromCold.paintedBands())
        assertEquals(listOf(600_000, 40_000),
            fromCold.observe(640_000, 1_000).paintedBands())
    }

    @Test fun oldestBandsCoalesceAndNewestEightRemainDistinct() {
        var history = ProgressChunkHistory().observe(0, 1)
        repeat(27) { i -> history = history.observe((i + 1) * 10_000, 1) }
        assertEquals(20, history.bands.size)
        assertEquals(270_000, history.paintedBands().sum())
        assertEquals(80_000, history.bands.first())
        assertEquals(List(8) { 10_000 }, history.bands.takeLast(8))
    }

    @Test fun subpixelGrowthAccumulatesButAlwaysPaintsItsTrueTotal() {
        var history = ProgressChunkHistory().observe(200_000, 4_000)
        repeat(20) { i ->
            history = history.observe(200_000 + (i + 1) * 100, 4_000)
        }
        assertEquals(listOf(202_000), history.paintedBands())
        assertEquals(2_000, history.pendingPpm)
        history = history.observe(204_000, 4_000)
        assertEquals(listOf(200_000, 4_000), history.paintedBands())
        assertEquals(0, history.pendingPpm)
    }

    @Test fun lastTinyIncrementFillsOneHundredPercentExactly() {
        val almost = ProgressChunkHistory().observe(999_900, 3_000)
        val done = almost.observe(1_000_000, 3_000)
        assertEquals(listOf(1_000_000), done.paintedBands())
        assertEquals(1_000_000, done.paintedBands().sum())
    }

    @Test fun trustedBackwardRecheckTrimsRightmostBands() {
        val history = ProgressChunkHistory().observe(0, 1)
            .observe(600_000, 1).observe(750_000, 1)
        val corrected = history.observe(700_000, 1)
        assertEquals(listOf(600_000, 100_000), corrected.paintedBands())
        assertEquals(emptyList<Int>(), corrected.observe(0, 1).paintedBands())
    }

    @Test fun correctionConsumesPendingBeforeOlderBands() {
        val prior = ProgressChunkHistory().observe(500_000, 5_000)
            .observe(502_000, 5_000)
        assertEquals(listOf(502_000), prior.paintedBands())
        val next = prior.observe(499_000, 5_000)
        assertEquals(listOf(499_000), next.paintedBands())
        assertEquals(0, next.pendingPpm)
    }

    @Test fun serializedStateRoundTripsAndRejectsCorruption() {
        val history = ProgressChunkHistory().observe(0, 3_000)
            .observe(130_000, 3_000).observe(131_000, 3_000)
        assertEquals(history, ProgressChunkHistory.decode(history.encode()))
        assertNull(ProgressChunkHistory.decode("v1|200000|0|100000"))
        assertNull(ProgressChunkHistory.decode("v1|100000|0|0,100000"))
        assertNull(ProgressChunkHistory.decode("v1|100000|0|x"))
        assertNull(ProgressChunkHistory.decode("v2|100000|0|100000"))
        assertNull(ProgressChunkHistory.decode("v1|100000|0|" + List(21) { "1" }.joinToString(",")))
        assertFalse(history.paintedBands().isEmpty())
        assertTrue(history.paintedBands().size <= ProgressChunkHistory.MAX_BANDS)
    }
}
