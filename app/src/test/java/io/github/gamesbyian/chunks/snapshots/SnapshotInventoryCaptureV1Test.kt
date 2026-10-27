package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SnapshotInventoryCaptureV1Test {
    private val h1 = "a".repeat(40)
    private val h2 = "b".repeat(40)
    private val clock = Instant.parse("2026-10-27T19:00:00Z")

    private fun remote(hash: String, name: String, directory: String = "/home/private/secret") =
        RemoteTransfer(
            hash = hash, name = name, sizeBytes = 100L, completedBytes = 99L,
            started = true, directory = directory)

    @Test fun remoteInventoryExportsOnlyWhatReadOnlyRpcActuallyKnows() {
        val captured = SnapshotInventoryCaptureV1.remote(listOf(
            remote(h1.uppercase(), "Show 😀"),
            remote(h2, "Episode 02")), clock)
        assertEquals(2, captured.sourceCount)
        assertEquals(2, captured.inventoriedCount)
        assertEquals(0, captured.verifiedRestorableCount)
        assertTrue(captured.omitted.isEmpty())
        assertEquals(SnapshotManifestV1.Source.RTORRENT, captured.manifest.source)
        val archive = captured.toArchive()
        val verified = SnapshotManifestV1.decode(archive)
        assertEquals(0, verified.restorableCount)
        assertEquals(2, verified.inventoryOnlyCount)
        assertEquals(setOf(h1, h2), verified.manifest.items.map { it.infoHashV1 }.toSet())
        assertTrue(verified.metainfoByHash.isEmpty())
        assertFalse(archive.toString(Charsets.ISO_8859_1).contains("/home/private/secret"))
        assertTrue(verified.manifest.items.all { it.sourceDirectoryHint == null })
    }

    @Test fun historicalServerDirectoriesAreOptInHintsAndNeverRecoveryGuarantees() {
        val inventory = listOf(remote(h1, "Episode", "/seedbox/media"))
        val default = SnapshotInventoryCaptureV1.remote(inventory, clock)
        val opted = SnapshotInventoryCaptureV1.remote(
            inventory, clock, includeHistoricalDirectories = true)
        assertNull(default.manifest.items.single().sourceDirectoryHint)
        val verified = SnapshotManifestV1.decode(opted.toArchive())
        assertEquals("/seedbox/media", verified.manifest.items.single().sourceDirectoryHint)
        assertEquals(SnapshotManifestV1.Recovery.INVENTORY,
            verified.manifest.items.single().recovery)
        assertEquals(0, verified.restorableCount)
    }

    @Test fun avoidsPretendingV2AndUnsafeTransferNamesAreRestorable() {
        val captured = SnapshotInventoryCaptureV1.remote(listOf(
            remote("e".repeat(64), "v2-only"),
            remote(h1, "bad\nname"),
            remote(h2, "Safe Label")
        ), clock)
        assertEquals(3, captured.sourceCount)
        assertEquals(1, captured.inventoriedCount)
        assertEquals(2, captured.omitted.size)
        assertEquals(SnapshotInventoryCaptureV1.OmissionReason.UNSUPPORTED_IDENTITY,
            captured.omitted[0].reason)
        assertEquals(SnapshotInventoryCaptureV1.OmissionReason.UNSAFE_NAME,
            captured.omitted[1].reason)
        assertEquals(1, SnapshotManifestV1.decode(captured.toArchive()).inventoryOnlyCount)
    }

    @Test fun rejectsDuplicateObservedIdentitiesRatherThanPickingArbitraryVersion() {
        try {
            SnapshotInventoryCaptureV1.remote(
                listOf(remote(h1, "old"), remote(h1.uppercase(), "new")), clock)
            fail("Conflicting duplicate remote rows must not be silently collapsed")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun oversizedRemoteInventoriesRequireExplicitSplitAndCannotSilentlyTruncate() {
        try {
            SnapshotInventoryCaptureV1.remote(
                List(513) { remote(h1, "row " + it) }, clock)
            fail("Export must not silently omit transfer 513")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun localStatusOnlyMeansInventoryOnlyEvenIfProgressSaysComplete() {
        val local = LocalTransferStatus(
            infoHash = h1, name = "Local complete", state = LocalTransferState.SEEDING,
            stopped = false, progressPpm = 1_000_000, wantedBytes = 100,
            doneBytes = 100, payloadDownloadedBytes = 100, payloadUploadedBytes = 0,
            peers = 0, savePath = "/internal/files", error = null)
        val captured = SnapshotInventoryCaptureV1.local(listOf(local), clock)
        assertEquals(SnapshotManifestV1.Source.LOCAL, captured.manifest.source)
        assertEquals(0, SnapshotManifestV1.decode(captured.toArchive()).restorableCount)
        assertTrue(captured.toArchive().contentEquals(captured.toArchive()))
    }

    @Test fun unsafeDirectoryHintIsOmittedAndReportedNotEmbedded() {
        val captured = SnapshotInventoryCaptureV1.remote(
            listOf(remote(h1, "Safe", "/path\u0000with-control")), clock,
            includeHistoricalDirectories = true)
        assertEquals(1, captured.omittedDirectoryHints)
        assertNull(SnapshotManifestV1.decode(captured.toArchive()).manifest.items[0].sourceDirectoryHint)
    }
}
