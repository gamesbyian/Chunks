package io.github.gamesbyian.chunks.rpc

import org.junit.Assert.*
import org.junit.Test

class RemoteQueueActivityContextTest {
    private val stalled = RemoteTransfer(
        hash = "ab".repeat(20), name = "Stalled", sizeBytes = 1000,
        completedBytes = 50, started = true, directory = "/test",
        downBytesPerSecond = 0)
    private val other = RemoteTransfer(
        hash = "cd".repeat(20), name = "Other", sizeBytes = 1000,
        completedBytes = 250, started = true, directory = "/test",
        downBytesPerSecond = 400)
    private fun observed(target: RemoteTransfer, view: List<RemoteTransfer>, fresh: Boolean = true) =
        RemoteQueueActivityContext.otherTransferReportsDownloading(
            target, RemoteQueueActivityContext.countReportingDownloadTraffic(view), fresh
        )

    @Test fun onlyPositiveRateOnDifferentTransferInFreshViewQualifies() {
        assertTrue(observed(stalled, listOf(stalled, other)))
        assertTrue(observed(stalled, listOf(stalled, other.copy(hash = other.hash.uppercase()))))
        assertFalse(observed(stalled, listOf(stalled, other), fresh = false))
        assertFalse(observed(stalled, listOf(stalled)))
        assertFalse(observed(stalled, listOf(stalled, other.copy(downBytesPerSecond = 0))))
        assertFalse(observed(stalled, listOf(stalled, other.copy(downBytesPerSecond = null))))
    }

    @Test fun noContextForStoppedCompletedUnknownOrSelfTraffic() {
        val view = listOf(stalled, other)
        assertFalse(observed(stalled.copy(started = false), view))
        assertFalse(observed(stalled.copy(completedBytes = 1000), view))
        assertFalse(observed(stalled.copy(downBytesPerSecond = null), view))
        assertFalse(observed(stalled.copy(downBytesPerSecond = 10), view))
        assertFalse(observed(stalled.copy(sizeBytes = 0), view))
        assertFalse(observed(stalled, listOf(stalled, stalled.copy(downBytesPerSecond = 100))))
    }

    @Test fun countsOnlyKnownPositiveRatesOncePerLargeView() {
        val lots = List(12_000) { index ->
            other.copy(
                hash = index.toString(16).padStart(40, '0'),
                downBytesPerSecond = if (index == 11_999) 42L else null
            )
        }
        assertEquals(1, RemoteQueueActivityContext.countReportingDownloadTraffic(lots))
        assertTrue(observed(stalled, lots))
    }

    @Test fun noClaimOfGlobalTrafficFromAFilteredView() {
        // The known loaded view, not the whole rTorrent server, is authoritative.
        assertFalse(observed(stalled, listOf(stalled)))
        assertTrue(observed(stalled, listOf(stalled, other)))
    }
}
