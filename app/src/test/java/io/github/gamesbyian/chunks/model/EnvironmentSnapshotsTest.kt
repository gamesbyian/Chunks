package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.*
import org.junit.Test

class EnvironmentSnapshotsTest {
    private fun local(hash: String) = LocalTransferStatus(
        infoHash = hash, name = "n", state = LocalTransferState.DOWNLOADING, stopped = false,
        progressPpm = 0, wantedBytes = 10, doneBytes = 0, payloadDownloadedBytes = 0,
        payloadUploadedBytes = 0, peers = 0, savePath = "/x", error = null, metadataKnown = true)

    private fun remote(hash: String) = RemoteTransfer(hash, "n", 10, 0, true, "/x")
    private val server = EnvironmentSnapshots.serverId("https://example.org/RPC2", "user")

    @Test fun serverIdIsStableTrimmedAndDistinguishesNumberedInstances() {
        assertEquals(server, EnvironmentSnapshots.serverId(" https://example.org/RPC2 ", "user "))
        assertNotEquals(server, EnvironmentSnapshots.serverId("https://example.org/rutorrent2/RPC2", "user"))
        assertNotEquals(server, EnvironmentSnapshots.serverId("https://example.org/RPC2", "other"))
        assertTrue(server.value.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(EnvironmentSnapshots.PHONE, server)
    }

    @Test fun phoneSnapshotReportsStaleLockedAndUnconfirmedHonestly() {
        val frame = LocalInventoryFrame(listOf(local("a".repeat(40))), stale = false)
        val live = EnvironmentSnapshots.phone(frame, confirmed = true, locked = false, observedAtEpochMillis = 5)
        assertTrue(live.canClaimCompleteLiveInventory)
        assertEquals(1, live.transfers.size)
        val stale = EnvironmentSnapshots.phone(frame.copy(stale = true), true, false, 5)
        assertEquals(EnvironmentAvailability.STALE, stale.availability)
        assertFalse(stale.canClaimCompleteLiveInventory)
        // An initial empty list before any accepted read is not an observed empty engine.
        assertFalse(EnvironmentSnapshots.phone(LocalInventoryFrame(emptyList(), false), false, false, null)
            .inventoryComplete)
        val locked = EnvironmentSnapshots.phone(frame, true, locked = true, observedAtEpochMillis = 5)
        assertEquals(EnvironmentAvailability.LOCKED, locked.availability)
        assertTrue("locked rows must not leak", locked.transfers.isEmpty())
    }

    @Test fun invalidOrDuplicateHashesAreDroppedAndMarkIncomplete() {
        val good = "b".repeat(40)
        val dup = "c".repeat(40)
        val phone = EnvironmentSnapshots.phone(LocalInventoryFrame(listOf(local(good), local("not-a-hash"),
            local(dup), local(dup.uppercase())), false), true, false, 1)
        assertEquals(listOf(good), phone.transfers.map { it.key.infoHash })
        assertFalse(phone.inventoryComplete)
        val remote = EnvironmentSnapshots.server(server, listOf(remote(good), remote("zz")),
            connected = true, stale = false, locked = false, fullView = true, observedAtEpochMillis = 1)
        assertEquals(listOf(good), remote.transfers.map { it.key.infoHash })
        assertFalse(remote.inventoryComplete)
    }

    @Test fun serverSnapshotNeedsConnectionFullViewAndObservationToBeComplete() {
        val rows = listOf(remote("d".repeat(40)))
        val full = EnvironmentSnapshots.server(server, rows, true, false, false, true, 9)
        assertTrue(full.canClaimCompleteLiveInventory)
        assertFalse(EnvironmentSnapshots.server(server, rows, true, false, false, fullView = false,
            observedAtEpochMillis = 9).inventoryComplete)
        assertFalse(EnvironmentSnapshots.server(server, rows, true, false, false, true, null).inventoryComplete)
        val offline = EnvironmentSnapshots.server(server, rows, connected = false, stale = false,
            locked = false, fullView = true, observedAtEpochMillis = 9)
        assertEquals(EnvironmentAvailability.OFFLINE, offline.availability)
        assertTrue(offline.transfers.isEmpty())
        assertEquals(EnvironmentAvailability.LOCKED, EnvironmentSnapshots.server(server, rows, true,
            false, locked = true, fullView = true, observedAtEpochMillis = 9).availability)
    }

    @Test fun aliasScopeParsesToTheSameServerIdOrRejectsMalformedScopes() {
        assertEquals(server, EnvironmentSnapshots.serverIdOfAliasScope("https://example.org/RPC2\u0000user"))
        assertNull(EnvironmentSnapshots.serverIdOfAliasScope("https://example.org/RPC2"))
        assertNull(EnvironmentSnapshots.serverIdOfAliasScope("\u0000user"))
        assertNull(EnvironmentSnapshots.serverIdOfAliasScope("a\u0000b\u0000c"))
    }
}
