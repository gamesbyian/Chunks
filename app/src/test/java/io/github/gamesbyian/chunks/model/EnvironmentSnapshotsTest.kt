package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import kotlinx.coroutines.runBlocking
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
        val withoutTimestamp = EnvironmentSnapshots.phone(frame, confirmed = true,
            locked = false, observedAtEpochMillis = null)
        assertFalse("A confirmed flag cannot replace the last successful observation",
            withoutTimestamp.inventoryComplete)
        assertFalse(withoutTimestamp.canClaimCompleteLiveInventory)
        val constructed = EnvironmentTransferSnapshot(EnvironmentSnapshots.PHONE,
            EnvironmentAvailability.LIVE, inventoryComplete = true, observedAtEpochMillis = null,
            transfers = live.transfers)
        assertFalse("A manually constructed partial snapshot cannot claim complete live coverage",
            constructed.canClaimCompleteLiveInventory)
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
        val full = EnvironmentSnapshots.server(server, rows, true, false, false, true, 9,
            nowEpochMillis = 9)
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

    @Test fun signedInServerExpiresAtTheSameBoundaryAsSavedListings() {
        val observed = 1_000_000L
        val rows = listOf(remote("d".repeat(40)))
        fun at(now: Long) = EnvironmentSnapshots.server(server, rows, connected = true,
            stale = false, locked = false, fullView = true, observedAtEpochMillis = observed,
            nowEpochMillis = now)
        for (now in listOf(observed, observed + EnvironmentSnapshots.SERVER_INVENTORY_FRESH_MS)) {
            assertTrue(at(now).canClaimCompleteLiveInventory)
            assertEquals(EnvironmentAvailability.LIVE, at(now).availability)
        }
        for (now in listOf(observed - 1, observed + EnvironmentSnapshots.SERVER_INVENTORY_FRESH_MS + 1)) {
            val stale = at(now)
            assertEquals(EnvironmentAvailability.STALE, stale.availability)
            assertFalse(stale.canClaimCompleteLiveInventory)
            assertEquals("Last-known rows must remain available for inspection", 1, stale.transfers.size)
            assertEquals(observed, stale.observedAtEpochMillis)
        }
        // Unknown observation time stays unconfirmed, never a fresh complete list.
        assertFalse(EnvironmentSnapshots.server(server, rows, true, false, false, true, null,
            nowEpochMillis = observed).canClaimCompleteLiveInventory)
        assertTrue(EnvironmentSnapshots.serverInventoryIsOutOfDate(Long.MIN_VALUE, Long.MAX_VALUE))
        assertTrue(EnvironmentSnapshots.serverInventoryIsOutOfDate(observed, Long.MIN_VALUE))
    }

    @Test fun expiredServerCannotContributeLiveRatesOrReceiveBatchCommands() = runBlocking {
        val observed = 1_000_000L
        val now = observed + EnvironmentSnapshots.SERVER_INVENTORY_FRESH_MS + 1
        val sameHash = "d".repeat(40)
        val otherServer = EnvironmentId("other-server")
        val row = remote(sameHash).copy(downBytesPerSecond = 50, upBytesPerSecond = 5)
        val stale = EnvironmentSnapshots.server(server, listOf(row), true, false, false, true,
            observed, nowEpochMillis = now)
        val live = EnvironmentSnapshots.server(otherServer, listOf(row), true, false, false, true,
            now, nowEpochMillis = now)
        val query = UnifiedTransferCatalog.query(listOf(stale, live), UnifiedTransferQuery())
        assertEquals(2, query.transfers.size)
        assertTrue(query.hasIncompleteOrStaleSource)
        val totals = UnifiedTransferTotals.of(query)
        assertEquals(1, totals.liveRows)
        assertEquals(50L, totals.downloadBytesPerSecond)
        assertEquals(5L, totals.uploadBytesPerSecond)
        assertEquals(1, totals.excludedEnvironments)

        val calls = ArrayList<EnvironmentId>()
        val report = UnifiedBatchLifecycle.run(
            targets = query.transfers.map { UnifiedBatchLifecycle.Target(it.key, it.started) },
            start = false, sources = listOf(stale, live),
            adapters = listOf(server, otherServer).associateWith { id ->
                UnifiedBatchLifecycle.Adapter { _, _ -> calls += id; LifecycleReply.VERIFIED }
            })
        assertEquals(listOf(otherServer), calls)
        assertEquals(CommandEvidence.REFUSED, report.items.first { it.key.environment == server }.evidence)
        assertEquals(CommandEvidence.VERIFIED, report.items.first { it.key.environment == otherServer }.evidence)

        // Only an actual newly accepted read restores live status and totals.
        val refreshed = EnvironmentSnapshots.server(server, listOf(row), true, false, false, true,
            now, nowEpochMillis = now)
        assertTrue(refreshed.canClaimCompleteLiveInventory)
        assertEquals(2, UnifiedTransferTotals.of(UnifiedTransferCatalog.query(listOf(refreshed, live),
            UnifiedTransferQuery())).liveRows)
    }

    @Test fun aliasScopeParsesToTheSameServerIdOrRejectsMalformedScopes() {
        assertEquals(server, EnvironmentSnapshots.serverIdOfAliasScope("https://example.org/RPC2\u0000user"))
        assertNull(EnvironmentSnapshots.serverIdOfAliasScope("https://example.org/RPC2"))
        assertNull(EnvironmentSnapshots.serverIdOfAliasScope("\u0000user"))
        assertNull(EnvironmentSnapshots.serverIdOfAliasScope("a\u0000b\u0000c"))
    }
}
