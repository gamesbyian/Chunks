package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import org.junit.Assert.assertEquals
import org.junit.Test

/** Truthful, read-only user wording for local and rTorrent observations. */
class TransferAtAGlanceTest {
    private val key = UnifiedTransferKey.of(EnvironmentId("local"), "f".repeat(40))
    private fun transfer(
        started: Boolean? = null,
        progress: Int? = null,
        down: Long? = null,
        up: Long? = null,
        error: Boolean = false
    ) = UnifiedTransferObservation(key, "Sample", "Sample", 100L, progress,
        started, down, up, null, hasError = error)

    @Test fun neverTreatsAnUnreportedRateAsVerifiedTraffic() {
        assertEquals("Current activity unknown.", transferAtAGlance(transfer()))
        assertEquals("Started; download activity not confirmed.",
            transferAtAGlance(transfer(started = true)))
        assertEquals("Started; download activity not confirmed.",
            transferAtAGlance(transfer(started = true, down = 0)))
    }

    @Test fun reportedActivityIsPreciselyLabelledWithoutClaimingVerifiedBytes() {
        assertEquals("Download activity reported.",
            transferAtAGlance(transfer(started = true, progress = 500_000, down = 23)))
        assertEquals("Stopped in the last report.",
            transferAtAGlance(transfer(started = false, down = 23)))
    }

    @Test fun completedDownloadDoesNotProveSeedingOrFileAvailability() {
        assertEquals("Download complete; sharing activity not confirmed.",
            transferAtAGlance(transfer(started = true, progress = 1_000_000)))
        assertEquals("Download complete; upload activity reported.",
            transferAtAGlance(transfer(started = true, progress = 1_000_000, up = 50)))
        assertEquals("Current activity unknown.",
            transferAtAGlance(transfer(started = null, progress = 0)))
    }

    @Test fun reportedErrorWinsOverAllOptimisticStatusDescriptions() {
        assertEquals("Needs attention: an error was reported.",
            transferAtAGlance(transfer(started = true, progress = 1_000_000,
                down = 20, up = 5, error = true)))
    }
}
