package io.github.gamesbyian.chunks.snapshots

import java.util.Locale

/**
 * Read-only missing-only import proposal. The target inventory MUST be fully
 * loaded from one engine/profile before callers can claim an item is absent.
 *
 * No Add RPC, storage-path change, source deletion or local-engine operation is
 * exposed by this class. A review candidate is NOT an executable transaction:
 * its target Place, capability, privacy, consent and exact-hash postcondition
 * remain independent gates.
 */
internal object SnapshotRestorePreviewV1 {
    enum class Decision {
        ALREADY_PRESENT,
        METADATA_NEEDS_REVIEW,
        MAGNET_NEEDS_REVIEW,
        INVENTORY_ONLY,
        SOURCE_FAILED
    }

    data class Entry(
        val infoHashV1: String,
        val originalName: String,
        val decision: Decision,
        /** Missing, potentially recoverable records are selected for review. */
        val selectedForReview: Boolean,
        /** Untrusted historical display suggestion, never a target Place. */
        val historicalDirectoryHint: String?
    )

    data class Preview(
        val entries: List<Entry>,
        val targetInventoryCount: Int
    ) {
        val alreadyPresentCount: Int get() = entries.count {
            it.decision == Decision.ALREADY_PRESENT
        }
        val candidateCount: Int get() = entries.count { it.selectedForReview }
        val unavailableCount: Int get() = entries.count {
            it.decision == Decision.INVENTORY_ONLY || it.decision == Decision.SOURCE_FAILED
        }
        /** Never implies Add completed or bytes transferred. */
        val mutationsExecuted: Int get() = 0
    }

    fun plan(
        archive: SnapshotManifestV1.Verified,
        targetObservedHashes: Collection<String>,
        targetInventoryComplete: Boolean
    ): Preview {
        require(targetInventoryComplete) {
            "Target inventory is not complete; cannot safely identify missing transfers"
        }
        require(targetObservedHashes.size <= 20_000) {
            "Target inventory too large for a bounded restore comparison"
        }
        val normalized = targetObservedHashes.map { it.lowercase(Locale.ROOT) }
        require(normalized.all { Regex("[0-9a-f]{40}").matches(it) }) {
            "Target inventory contains unsupported or malformed v1 identity"
        }
        require(normalized.size == normalized.toSet().size) {
            "Target inventory contains duplicate identities"
        }
        val targetSet = normalized.toHashSet()
        val rows = archive.manifest.items.map { item ->
            val decision = when {
                item.infoHashV1 in targetSet -> Decision.ALREADY_PRESENT
                item.recovery == SnapshotManifestV1.Recovery.METAINFO ->
                    Decision.METADATA_NEEDS_REVIEW
                item.recovery == SnapshotManifestV1.Recovery.MAGNET ->
                    Decision.MAGNET_NEEDS_REVIEW
                item.recovery == SnapshotManifestV1.Recovery.INVENTORY ->
                    Decision.INVENTORY_ONLY
                else -> Decision.SOURCE_FAILED
            }
            Entry(
                infoHashV1 = item.infoHashV1,
                originalName = item.originalName,
                decision = decision,
                selectedForReview = decision == Decision.METADATA_NEEDS_REVIEW ||
                    decision == Decision.MAGNET_NEEDS_REVIEW,
                historicalDirectoryHint = item.sourceDirectoryHint
            )
        }
        return Preview(rows, targetSet.size)
    }
}
