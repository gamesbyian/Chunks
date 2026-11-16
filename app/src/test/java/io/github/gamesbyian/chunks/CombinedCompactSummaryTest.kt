package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.UnifiedTransferTotals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The primary Transfers header is one compact inventory + live-rate sentence. */
class CombinedCompactSummaryTest {
    @Test fun oneVisibleLineIncludesCountAndBothLiveSpeeds() {
        val text = combinedCompactSummaryText(49, 49,
            UnifiedTransferTotals(49, 0, 680448, 0, 0), false, includedClients = 2)
        assertTrue(text.startsWith("49 transfers · Live ↓ 0 B/s · ↑ "))
        assertFalse(text.contains("excluded"))
        assertFalse(text.contains("unknown"))
    }

    @Test fun narrowedAndUnverifiedSourcesAreNeverRepresentedAsCompleteLiveTotals() {
        val text = combinedCompactSummaryText(3, 151,
            UnifiedTransferTotals(2, 2048, 0, 1, 1), true, includedClients = 3)
        assertTrue(text.startsWith("3 of 151 transfers · Live ↓ "))
        assertTrue(text.contains("1 rates unknown"))
        assertTrue(text.contains("3 clients included"))
    }

    @Test fun overflowDoesNotWrapAndMissingSourcesNeverMasqueradeAsZero() {
        // Overflow needs live rows (totals sum only fully observed sources).
        val text = combinedCompactSummaryText(4, 4,
            UnifiedTransferTotals(2, 0, 0, 0, 2,
                downloadTotalBeyondRange = true), true, includedClients = 3)
        assertTrue(text.contains("above reporting range"))
        assertTrue(text.contains("3 clients included"))
    }

    @Test fun onlyExcludedSourcesNeverClaimLiveZeroRates() {
        // Stale server rows were visible while the header read "Live ↓ 0 B/s · ↑ 0 B/s".
        val text = combinedCompactSummaryText(2, 2,
            UnifiedTransferTotals(0, 0, 0, 0, 3), true, includedClients = 3)
        assertTrue(text.startsWith("2 transfers · No live rates"))
        assertFalse(text.contains("Live ↓"))
        assertFalse(text.contains("0 B/s"))
        assertTrue(text.contains("3 clients included"))
        // A fully observed source with no transfers is still a genuine live zero.
        assertTrue(combinedCompactSummaryText(0, 0, UnifiedTransferTotals(0, 0, 0, 0, 0), false, includedClients = 2)
            .startsWith("0 transfers · Live ↓ 0 B/s"))
    }

    @Test fun singleTransferUsesSingularNoun() {
        val text = combinedCompactSummaryText(1, 1,
            UnifiedTransferTotals(1, 0, 0, 0, 0), false, includedClients = 2)
        assertTrue(text.startsWith("1 transfer ·"))
    }
}
