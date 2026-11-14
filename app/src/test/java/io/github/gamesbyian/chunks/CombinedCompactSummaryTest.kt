package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.UnifiedTransferTotals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The primary Transfers header is one compact inventory + live-rate sentence. */
class CombinedCompactSummaryTest {
    @Test fun oneVisibleLineIncludesCountAndBothLiveSpeeds() {
        val text = combinedCompactSummaryText(49, 49,
            UnifiedTransferTotals(49, 0, 680448, 0, 0), false)
        assertTrue(text.startsWith("49 transfers · Live ↓ 0 B/s · ↑ "))
        assertFalse(text.contains("excluded"))
        assertFalse(text.contains("unknown"))
    }

    @Test fun narrowedAndUnverifiedSourcesAreNeverRepresentedAsCompleteLiveTotals() {
        val text = combinedCompactSummaryText(3, 151,
            UnifiedTransferTotals(2, 2048, 0, 1, 1), true)
        assertTrue(text.startsWith("3 of 151 transfers · Live ↓ "))
        assertTrue(text.contains("1 rates unknown"))
        assertTrue(text.contains("1 sources excluded"))
    }

    @Test fun overflowDoesNotWrapAndMissingSourcesNeverMasqueradeAsZero() {
        val text = combinedCompactSummaryText(0, 0,
            UnifiedTransferTotals(0, 0, 0, 0, 2,
                downloadTotalBeyondRange = true), true)
        assertTrue(text.contains("above reporting range"))
        assertTrue(text.contains("2 sources excluded"))
    }

    @Test fun singleTransferUsesSingularNoun() {
        val text = combinedCompactSummaryText(1, 1,
            UnifiedTransferTotals(1, 0, 0, 0, 0), false)
        assertTrue(text.startsWith("1 transfer ·"))
    }
}
