package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.assertEquals
import org.junit.Test

/** rTorrent rows use the local device's words, and only observed facts earn "Downloading". */
class RemoteStatusLabelTest {
    private val base = RemoteTransfer("a".repeat(40), "x", 1000, 500, true, "/d", 0, 0)

    @Test fun startedFlagAloneIsNotDownloading() {
        assertEquals("Started", RemoteStatusLabel.of(base, stale = false).label)
        assertEquals("Downloading", RemoteStatusLabel.of(base.copy(downBytesPerSecond = 2048), stale = false).label)
        assertEquals("a stale rate is not live activity",
            "Started", RemoteStatusLabel.of(base.copy(downBytesPerSecond = 2048), stale = true).label)
    }

    @Test fun completeStoppedAndMetadataStates() {
        assertEquals("Sharing", RemoteStatusLabel.of(base.copy(completedBytes = 1000), false).label)
        assertEquals("Stopped", RemoteStatusLabel.of(base.copy(started = false, completedBytes = 1000), false).label)
        assertEquals("Fetching metadata", RemoteStatusLabel.of(base.copy(metadataPending = true), false).label)
        assertEquals(ChunksTone.COMPLETE, RemoteStatusLabel.of(base.copy(completedBytes = 1000), false).tone)
    }
}
