package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cadence policy for automatic read-only restore of saved, non-active profiles. */
class SavedProfileAutoReadTest {
    private val scope = "https://a.example/RPC2\u0000ann"
    private val ok = SavedServerListing(listOf(RemoteTransfer("a".repeat(40), "ISO", 1, 1, true, "/d")),
        observedAtEpochMillis = 1_000, sourceAliasScope = scope)

    @Test fun neverReadIdentityIsDueImmediately() {
        assertTrue(SavedProfileAutoRead.due(null, null, now = 5))
    }

    @Test fun retiredRowsAreRestoredAfresh() {
        // Lock/account change removed the listing; the same identity reads again.
        assertTrue(SavedProfileAutoRead.due(null, lastAttemptAt = 1_000, now = 1_001))
    }

    @Test fun inFlightReadIsNeverDuplicated() {
        assertFalse(SavedProfileAutoRead.due(ok.copy(loading = true), null, now = 10_000_000))
        assertFalse(SavedProfileAutoRead.due(ok.copy(loading = true), 0, now = 10_000_000))
    }

    @Test fun healthyListingRefreshesInsideTheFreshnessWindow() {
        val t = 1_000L
        assertFalse(SavedProfileAutoRead.due(ok, t, t + SavedProfileAutoRead.HEALTHY_REFRESH_MS - 1))
        assertTrue(SavedProfileAutoRead.due(ok, t, t + SavedProfileAutoRead.HEALTHY_REFRESH_MS))
        assertTrue("refresh must precede the stale threshold",
            SavedProfileAutoRead.HEALTHY_REFRESH_MS < SAVED_LISTING_FRESH_MS)
    }

    @Test fun failedListingBacksOffInsteadOfHammeringTheServer() {
        val failed = ok.copy(error = "connection refused")
        val t = 1_000L
        assertFalse(SavedProfileAutoRead.due(failed, t, t + SavedProfileAutoRead.HEALTHY_REFRESH_MS))
        assertFalse(SavedProfileAutoRead.due(failed, t, t + SavedProfileAutoRead.FAILED_RETRY_MS - 1))
        assertTrue(SavedProfileAutoRead.due(failed, t, t + SavedProfileAutoRead.FAILED_RETRY_MS))
    }

    @Test fun backwardsClockDoesNotSuppressReadsForever() {
        assertTrue(SavedProfileAutoRead.due(ok, lastAttemptAt = 50_000, now = 10))
    }
}
