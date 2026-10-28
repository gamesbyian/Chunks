package io.github.gamesbyian.chunks.snapshots

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class SnapshotManualAddReviewV1Test {
    private val info = ("d6:lengthi5e4:name8:test.txt12:piece lengthi16384e" +
        "6:pieces20:01234567890123456789e").toByteArray()
    private val torrent = ("d4:info" + info.toString(Charsets.UTF_8) + "e").toByteArray()
    private val hash = MessageDigest.getInstance("SHA-1").digest(info)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val other = "b".repeat(40)
    private val original = SnapshotManifestV1.decode(SnapshotManifestV1.encode(
        SnapshotManifestV1.Manifest(
            "chunks-android", "2026-10-27T20:00:00Z",
            SnapshotManifestV1.Source.LOCAL, listOf(
                SnapshotManifestV1.Item(hash, "test.txt",
                    SnapshotManifestV1.Recovery.METAINFO)
            )
        ), mapOf(hash to torrent)
    ))
    private val entry = SnapshotRestorePreviewV1.plan(
        original, listOf(other), true).entries.single()

    private fun prepare(
        snapshot: SnapshotManifestV1.Verified = original,
        reviewed: SnapshotRestorePreviewV1.Entry = entry,
        before: List<String> = listOf(other),
        after: List<String> = listOf(other),
        beforeGeneration: Long = 7,
        afterGeneration: Long = 7,
        fresh: Boolean = true
    ) = SnapshotManualAddReviewV1.prepare(snapshot, reviewed, before, after,
        beforeGeneration, afterGeneration, fresh)

    @Test fun onlyVerifiedMissingDefinitionProducesExactOriginalDraft() {
        val bytes = prepare()
        assertArrayEquals(torrent, bytes)
        bytes[0] = 0
        assertArrayEquals(torrent, original.metainfoByHash.getValue(hash))
    }

    @Test fun stalePartialOrDifferentTargetCannotProduceAnAddDraft() {
        reject { prepare(after = listOf(hash, other)) }
        reject { prepare(after = listOf(other, other)) }
        reject { prepare(afterGeneration = 8) }
        reject { prepare(fresh = false) }
        reject { prepare(before = listOf(other.uppercase())) }
    }

    @Test fun existingOrNonrecoverableRowsOrSpoofedSnapshotsCannotPrepare() {
        val already = entry.copy(decision = SnapshotRestorePreviewV1.Decision.ALREADY_PRESENT,
            selectedForReview = false)
        reject { prepare(reviewed = already) }
        reject { prepare(reviewed = entry.copy(originalName = "wrong.txt")) }
        reject { prepare(reviewed = entry.copy(selectedForReview = false)) }
        reject { prepare(snapshot = original.copy(metainfoByHash =
            mapOf(hash to "corrupted".toByteArray())) }
        reject { prepare(before = listOf(hash), after = listOf(hash)) }
    }

    private fun reject(block: () -> Unit) {
        try { block(); fail("Unverified or stale Add draft was permitted") }
        catch (_: IllegalArgumentException) {}
    }
}
