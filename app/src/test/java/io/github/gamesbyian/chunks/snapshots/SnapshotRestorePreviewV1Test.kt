package io.github.gamesbyian.chunks.snapshots

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class SnapshotRestorePreviewV1Test {
    private val info = ("d6:lengthi5e4:name8:test.txt12:piece lengthi16384e" +
        "6:pieces20:01234567890123456789e").toByteArray()
    private val torrent = ("d4:info" + info.toString(Charsets.UTF_8) + "e").toByteArray()
    private val completeHash = MessageDigest.getInstance("SHA-1").digest(info)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val magnetHash = "b".repeat(40)
    private val inventoryHash = "c".repeat(40)
    private val failedHash = "d".repeat(40)

    private fun verified(): SnapshotManifestV1.Verified {
        val manifest = SnapshotManifestV1.Manifest(
            producer = "chunks-android",
            createdAtUtc = "2026-10-27T19:00:00Z",
            source = SnapshotManifestV1.Source.RTORRENT,
            items = listOf(
                SnapshotManifestV1.Item(completeHash, "test.txt",
                    SnapshotManifestV1.Recovery.METAINFO,
                    sourceDirectoryHint = "/old/seedbox/media"),
                SnapshotManifestV1.Item(magnetHash, "Metadata fallback",
                    SnapshotManifestV1.Recovery.MAGNET,
                    magnet = "magnet:?xt=urn:btih:" + magnetHash + "&dn=Metadata"),
                SnapshotManifestV1.Item(inventoryHash, "Hash only",
                    SnapshotManifestV1.Recovery.INVENTORY),
                SnapshotManifestV1.Item(failedHash, "Unresolved",
                    SnapshotManifestV1.Recovery.FAILED)
            )
        )
        return SnapshotManifestV1.decode(SnapshotManifestV1.encode(
            manifest, mapOf(completeHash to torrent)))
    }

    @Test fun missingOnlySelectionClassifiesDuplicatesAndNonRestorableEntries() {
        val preview = SnapshotRestorePreviewV1.plan(
            verified(),
            targetObservedHashes = listOf(magnetHash.uppercase(), "a".repeat(64)),
            targetInventoryComplete = true)
        assertEquals(1, preview.alreadyPresentCount)
        assertEquals(1, preview.candidateCount)
        assertEquals(2, preview.unavailableCount)
        assertEquals(0, preview.mutationsExecuted)
        assertEquals(2, preview.targetInventoryCount)
        assertEquals(listOf(
            SnapshotRestorePreviewV1.Decision.METADATA_NEEDS_REVIEW,
            SnapshotRestorePreviewV1.Decision.ALREADY_PRESENT,
            SnapshotRestorePreviewV1.Decision.INVENTORY_ONLY,
            SnapshotRestorePreviewV1.Decision.SOURCE_FAILED
        ), preview.entries.map { it.decision })
        assertTrue(preview.entries[0].selectedForReview)
        assertFalse(preview.entries[1].selectedForReview)
        assertEquals("/old/seedbox/media", preview.entries[0].historicalDirectoryHint)
    }

    @Test fun noTargetTransfersMeansAllGenuineRecoveryStrategiesAreReviewCandidates() {
        val preview = SnapshotRestorePreviewV1.plan(verified(), emptyList(), true)
        assertEquals(2, preview.candidateCount)
        assertEquals(0, preview.alreadyPresentCount)
        assertEquals(2, preview.unavailableCount)
        assertEquals(0, preview.mutationsExecuted)
    }

    @Test fun rejectsPartialTargetInventoryAndCorruptOrDuplicateTargetIdentities() {
        rejected { SnapshotRestorePreviewV1.plan(verified(), emptyList(), false) }
        rejected { SnapshotRestorePreviewV1.plan(verified(), listOf("not-an-infohash"), true) }
        rejected { SnapshotRestorePreviewV1.plan(
            verified(), listOf(magnetHash, magnetHash.uppercase()), true) }
        rejected { SnapshotRestorePreviewV1.plan(
            verified(), List(20_001) { "a".repeat(40) }, true) }
    }

    @Test fun alreadyPresentMetainfoNeverGetsSelectedForBlindDuplicateAdd() {
        val preview = SnapshotRestorePreviewV1.plan(
            verified(), listOf(completeHash), true)
        val item = preview.entries.first { it.infoHashV1 == completeHash }
        assertEquals(SnapshotRestorePreviewV1.Decision.ALREADY_PRESENT, item.decision)
        assertFalse(item.selectedForReview)
        assertEquals(1, preview.candidateCount) // Missing magnet, still requires review.
    }

    private fun rejected(block: () -> Unit) {
        try {
            block()
            fail("Incomplete or contradictory target inventory must fail closed")
        } catch (_: IllegalArgumentException) { }
    }
}
