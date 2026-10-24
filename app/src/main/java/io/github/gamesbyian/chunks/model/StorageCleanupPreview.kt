package io.github.gamesbyian.chunks.model

/**
 * Non-mutating cleanup preview only. These inputs must eventually come from a verified
 * filesystem ownership/reference graph, NOT a filename or the rTorrent RPC session list.
 */
enum class CleanupObjectKind { ENGINE_BACKING, INDEPENDENT_LIBRARY_COPY, EXTRACTED_RESULT, APP_STAGING }
enum class CleanupDisposition { SAFE_CANDIDATE, SEEDING_IMPACT, BLOCKED }

data class CleanupPhysicalObject(
    val volumeId: String,
    val physicalId: String,
    val kind: CleanupObjectKind,
    val allocatedBytes: Long?,
    val ownershipVerified: Boolean,
    val inventoryFresh: Boolean,
    val activeWriter: Boolean,
    val otherPhysicalReferences: Int,
    val torrentReferences: Set<String>
) {
    init {
        require(volumeId.isNotBlank() && physicalId.isNotBlank())
        require(allocatedBytes == null || allocatedBytes >= 0)
        require(otherPhysicalReferences >= 0)
        require(torrentReferences.none { it.isBlank() })
    }
}

data class CleanupPreview(
    val physicalId: String,
    val disposition: CleanupDisposition,
    /** An *estimate* of potentially released blocks, never a deletion guarantee. */
    val potentialReleasedBytes: Long?,
    val explanation: String
)

data class CleanupBatchPreview(
    val items: List<CleanupPreview>,
    /** Null if any selectable object's allocation is unknown or their sum overflows. */
    val potentialReleasedBytes: Long?
)

object StorageCleanupPreview {
    fun inspect(objectId: CleanupPhysicalObject): CleanupPreview {
        val refs = objectId.torrentReferences.size
        val (classification, reason) = when {
            !objectId.inventoryFresh ->
                CleanupDisposition.BLOCKED to "Storage inventory is stale. Refresh and revalidate before any deletion."
            !objectId.ownershipVerified ->
                CleanupDisposition.BLOCKED to "File ownership is unverified; Chunks cannot safely delete it."
            objectId.activeWriter ->
                CleanupDisposition.BLOCKED to "The backing object may still be written. Stop and reconcile the writer first."
            objectId.otherPhysicalReferences > 0 ->
                CleanupDisposition.BLOCKED to "This physical object has other file references; reclaiming bytes is not proven."
            objectId.kind == CleanupObjectKind.ENGINE_BACKING && refs == 0 ->
                CleanupDisposition.BLOCKED to "Torrent backing mapping is unknown; an empty RPC reference list cannot prove safety."
            refs > 0 ->
                CleanupDisposition.SEEDING_IMPACT to
                    "Deleting this torrent-backed data will make affected pieces unavailable for sharing. " +
                    "Extracted copies do not replace source torrent bytes; restore or re-download to seed fully."
            else ->
                CleanupDisposition.SAFE_CANDIDATE to
                    "No known torrent dependencies; verify actual filesystem references and permissions at commit."
        }
        return CleanupPreview(
            physicalId = objectId.physicalId,
            disposition = classification,
            potentialReleasedBytes = if (classification == CleanupDisposition.BLOCKED) null
                else objectId.allocatedBytes,
            explanation = reason
        )
    }

    fun inspectBatch(objects: List<CleanupPhysicalObject>): CleanupBatchPreview {
        require(objects.map { it.volumeId to it.physicalId }.toSet().size == objects.size) {
            "Duplicate physical object identity could double-count space"
        }
        val previews = objects.map(::inspect)
        var total: Long? = 0L
        for (item in previews) {
            if (item.disposition == CleanupDisposition.BLOCKED) continue
            val bytes = item.potentialReleasedBytes
            val previous = total
            total = if (previous == null || bytes == null) null
                else runCatching { Math.addExact(previous, bytes) }.getOrNull()
        }
        return CleanupBatchPreview(previews, total)
    }
}
