package io.github.gamesbyian.chunks.snapshots

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SnapshotManualMagnetReviewV1Test {
    private val hash = "a".repeat(40)
    private val other = "b".repeat(40)
    private val magnet = "magnet:?xt=urn:btih:$hash&dn=Public%20sample&tr=https%3A%2F%2Ft.example%2Fa"

    private fun archive(link: String = magnet) = SnapshotManifestV1.decode(SnapshotManifestV1.encode(
        SnapshotManifestV1.Manifest("chunks-android", "2026-10-28T02:00:00Z",
            SnapshotManifestV1.Source.RTORRENT, listOf(
                SnapshotManifestV1.Item(hash, "Public sample", SnapshotManifestV1.Recovery.MAGNET,
                    magnet = link))), emptyMap()))

    private fun prepare(
        snapshot: SnapshotManifestV1.Verified = archive(),
        before: List<String> = listOf(other),
        after: List<String> = listOf(other),
        generation: Long = 3,
        fresh: Boolean = true
    ): String {
        val entry = SnapshotRestorePreviewV1.plan(snapshot, before, true).entries.single()
        return SnapshotManualAddReviewV1.prepareMagnet(snapshot, entry, before, after, 3, generation, fresh)
    }

    @Test fun missingMagnetRecordPrefillsExactlyItsOwnLink() {
        assertEquals(magnet, prepare())
    }

    @Test fun staleOrPresentOrMismatchedRecordsAreRefused() {
        reject { prepare(after = listOf(other, hash)) }
        reject { prepare(generation = 4) }
        reject { prepare(fresh = false) }
        reject { prepare(before = listOf(hash), after = listOf(hash)) }
        reject { prepare(snapshot = archive("magnet:?xt=urn:btih:" + "c".repeat(40))) }
        reject { prepare(snapshot = archive(magnet + "&xt=urn:btih:" + "c".repeat(40))) }
    }

    @Test fun refreshedMagnetTargetRequiresCompleteValidDistinctIdentities() {
        val snapshot = archive()
        val reviewed = SnapshotRestorePreviewV1.plan(snapshot, listOf(other), true).entries.single()
        // A caller must not turn a malformed/ambiguous unchanged inventory
        // into evidence that the reviewed torrent is absent.
        for (untrustworthy in listOf(
            listOf("not-a-hash"),
            listOf(other, other.uppercase()),
            listOf("g".repeat(40)),
            listOf("f".repeat(41))
        )) {
            reject {
                SnapshotManualAddReviewV1.prepareMagnet(snapshot, reviewed,
                    untrustworthy, untrustworthy, 3, 3, true)
            }
        }
        // A correctly formed v2 identity can coexist in the comparison;
        // this importer still restores only verified v1 snapshot records.
        assertEquals(magnet, SnapshotManualAddReviewV1.prepareMagnet(snapshot, reviewed,
            listOf("c".repeat(64)), listOf("c".repeat(64)), 3, 3, true))
    }

    private fun reject(block: () -> Unit) {
        try { block(); fail("Unsafe magnet draft was permitted") } catch (_: IllegalArgumentException) {}
    }
}
