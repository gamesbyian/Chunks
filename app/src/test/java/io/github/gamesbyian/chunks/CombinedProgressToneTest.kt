package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import org.junit.Assert.assertEquals
import org.junit.Test

/** All environments uses the same progress colour meaning as the phone and rTorrent cards. */
class CombinedProgressToneTest {
    private fun row(ppm: Int?, started: Boolean?, error: Boolean = false) = UnifiedTransferObservation(
        UnifiedTransferKey.of(EnvironmentId("phone"), "a".repeat(40)), "n", "n", 10, ppm, started, 0, 0, 0, hasError = error)

    @Test fun completeStoppedActiveAndErrorMatchTheOwningLists() {
        assertEquals(ChunksTone.COMPLETE, combinedProgressTone(row(1_000_000, true)))
        assertEquals(ChunksTone.COMPLETE, combinedProgressTone(row(1_000_000, false)))
        assertEquals(ChunksTone.PAUSED, combinedProgressTone(row(500_000, false)))
        assertEquals(ChunksTone.ACTIVE, combinedProgressTone(row(500_000, true)))
        assertEquals(ChunksTone.ACTIVE, combinedProgressTone(row(500_000, null)))
        assertEquals(ChunksTone.ERROR, combinedProgressTone(row(500_000, true, error = true)))
    }
}
