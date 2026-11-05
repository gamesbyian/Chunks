package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Same-list Similar Transfers sources: exact identities only, no duplicates, honest staleness. */
class SimilarSourceAdaptersTest {
    private fun local(hash: String) = LocalTransferStatus(infoHash = hash, name = "n", state = LocalTransferState.SEEDING,
        stopped = false, progressPpm = 1_000_000, wantedBytes = 1, doneBytes = 1, payloadDownloadedBytes = 0,
        payloadUploadedBytes = 0, peers = 0, savePath = "/x", error = null)

    @Test fun phoneSourceDropsDuplicatesAndMalformedHashes() {
        val s = phoneSimilarSource(listOf(local("a".repeat(40)), local("A".repeat(40)), local("not-a-hash")), stale = false)
        assertEquals(1, s.transfers.size)
        assertEquals(EnvironmentAvailability.LIVE, s.availability)
        assertEquals(EnvironmentAvailability.STALE, phoneSimilarSource(emptyList(), stale = true).availability)
    }

    @Test fun remoteSourceIsScopedToItsOwnEnvironmentAndMarksStale() {
        val env = EnvironmentId("rtorrent:x")
        val s = remoteSimilarSource(env, listOf(RemoteTransfer("b".repeat(40), "n", 1, 1, true, "/d")), stale = true)
        assertEquals(env, s.transfers.single().key.environment)
        assertFalse(s.canClaimCompleteLiveInventory)
    }
}
