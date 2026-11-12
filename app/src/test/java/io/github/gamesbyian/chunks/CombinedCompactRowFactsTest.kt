package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UnifiedTransferObservations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FOREIGN-PARTIAL-TORRENT-RECOVERY P0 presentation: while the phone engine hash-checks
 * already-present payload bytes, the canonical row says so instead of "Started", and the
 * percentage is never presented as downloaded progress by a different label.
 */
class CombinedCompactRowFactsTest {
    private val phone = EnvironmentId("phone")
    private fun status(state: LocalTransferState, stopped: Boolean = false, ppm: Int = 350_000) =
        LocalTransferStatus("a".repeat(40), "Episode", state, stopped,
            ppm, 1_000_000, 350_000, 0, 0, 0, "/downloads", null)

    @Test fun checkingExistingDataIsNamedWhileTheEngineVerifiesPresentBytes() {
        val checking = UnifiedTransferObservations.fromPhone(phone, status(LocalTransferState.CHECKING))
        assertEquals(true, checking.checkingExistingData)
        val facts = combinedCompactRowFacts(checking, fresh = true)
        assertTrue(facts, facts.startsWith("Checking existing data · 35%"))
        assertTrue(combinedRowFacts(checking, fresh = true).startsWith("Checking existing data · 35%"))

        val downloading = UnifiedTransferObservations.fromPhone(phone, status(LocalTransferState.DOWNLOADING))
        assertEquals(false, downloading.checkingExistingData)
        assertTrue(combinedCompactRowFacts(downloading, fresh = true).startsWith("Started · 35%"))

        // A stopped job is stopped even if the engine last reported a check in progress.
        val stopped = UnifiedTransferObservations.fromPhone(phone, status(LocalTransferState.CHECKING, stopped = true))
        assertTrue(combinedCompactRowFacts(stopped).startsWith("Stopped · 35%"))
    }

    @Test fun remoteSourcesThatCannotReportCheckingLeaveItUnknown() {
        val remote = UnifiedTransferObservations.fromRTorrent(EnvironmentId("rtorrent:one"),
            io.github.gamesbyian.chunks.rpc.RemoteTransfer("b".repeat(40), "Episode", 1000, 350, true, "/srv"))
        assertNull(remote.checkingExistingData)
        assertTrue(combinedCompactRowFacts(remote).startsWith("Started · 35%"))
    }
}
