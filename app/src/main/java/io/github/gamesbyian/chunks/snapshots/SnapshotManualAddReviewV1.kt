package io.github.gamesbyian.chunks.snapshots

import java.util.Locale

/**
 * A reviewed snapshot definition may populate the existing phone Add form.
 * This never mutates an engine or writes a file, and MUST NOT be mistaken for
 * an authorized journal-backed restore transaction. The user still reviews
 * an actual destination and separately confirms Add.
 */
internal object SnapshotManualAddReviewV1 {
    fun prepare(
        verified: SnapshotManifestV1.Verified,
        reviewedEntry: SnapshotRestorePreviewV1.Entry,
        frozenTargetHashes: List<String>,
        currentTargetHashes: List<String>,
        frozenGeneration: Long,
        currentGeneration: Long,
        completeFreshTarget: Boolean
    ): ByteArray {
        require(completeFreshTarget && frozenGeneration == currentGeneration &&
            frozenTargetHashes == currentTargetHashes) {
            "Phone inventory changed after snapshot comparison; reopen the archive"
        }
        require(reviewedEntry.decision ==
            SnapshotRestorePreviewV1.Decision.METADATA_NEEDS_REVIEW &&
            reviewedEntry.selectedForReview) {
            "Only missing verified torrent definitions can populate Add review"
        }
        val target = currentTargetHashes.map { it.lowercase(Locale.ROOT) }
        require(target.size == target.toSet().size &&
            target.all { it.matches(Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})")) }) {
            "Phone inventory contains an ambiguous identity"
        }
        require(reviewedEntry.infoHashV1 !in target) {
            "Torrent already exists in the phone inventory"
        }
        val matched = verified.manifest.items.singleOrNull {
            it.infoHashV1 == reviewedEntry.infoHashV1 &&
                it.originalName == reviewedEntry.originalName
        } ?: throw IllegalArgumentException("Snapshot row does not match reviewed torrent")
        require(matched.recovery == SnapshotManifestV1.Recovery.METAINFO) {
            "Original complete torrent definition is absent"
        }
        return SnapshotVerifiedTorrentDocumentV1
            .prepare(verified, reviewedEntry.infoHashV1).bytes
    }

    /**
     * The same fences for a magnet-only record: it can only prefill an Add
     * draft, and only when its btih is exactly the reviewed missing identity.
     */
    fun prepareMagnet(
        verified: SnapshotManifestV1.Verified,
        reviewedEntry: SnapshotRestorePreviewV1.Entry,
        frozenTargetHashes: List<String>,
        currentTargetHashes: List<String>,
        frozenGeneration: Long,
        currentGeneration: Long,
        completeFreshTarget: Boolean
    ): String {
        require(completeFreshTarget && frozenGeneration == currentGeneration &&
            frozenTargetHashes == currentTargetHashes) {
            "Target inventory changed after snapshot comparison; reopen the archive"
        }
        require(reviewedEntry.decision == SnapshotRestorePreviewV1.Decision.MAGNET_NEEDS_REVIEW &&
            reviewedEntry.selectedForReview) {
            "Only missing magnet records can populate an Add review"
        }
        val target = currentTargetHashes.map { it.lowercase(Locale.ROOT) }
        require(target.size == target.toSet().size && reviewedEntry.infoHashV1 !in target) {
            "Torrent already exists in the target inventory"
        }
        val matched = verified.manifest.items.singleOrNull {
            it.infoHashV1 == reviewedEntry.infoHashV1 && it.originalName == reviewedEntry.originalName
        } ?: throw IllegalArgumentException("Snapshot row does not match reviewed torrent")
        val magnet = requireNotNull(matched.magnet?.takeIf {
            matched.recovery == SnapshotManifestV1.Recovery.MAGNET
        }) { "Snapshot record has no magnet" }
        val btih = Regex("(?i)[?&]xt=urn:btih:([0-9a-f]{40})(?:&|$)").findAll(magnet)
            .map { it.groupValues[1].lowercase(Locale.ROOT) }.toList()
        require(magnet.startsWith("magnet:?") && btih == listOf(reviewedEntry.infoHashV1)) {
            "Snapshot magnet does not name exactly the reviewed identity"
        }
        return magnet
    }
}
