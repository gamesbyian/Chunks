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

    private fun reject(block: () -> Unit) {
        try { block(); fail("Unsafe magnet draft was permitted") } catch (_: IllegalArgumentException) {}
    }
}
