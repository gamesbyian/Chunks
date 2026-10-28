package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader

/**
 * Extract a *single*, exact-identity-verified v1 torrent definition from an
 * already-validated snapshot. The target is an independent SAF document, never
 * a torrent engine's backing files. Does not create an import or Add action.
 */
internal object SnapshotVerifiedTorrentDocumentV1 {
    private val hashPattern = Regex("[0-9a-f]{40}")
    private const val MAX_TORRENT_BYTES = 2 * 1024 * 1024

    data class Prepared(
        val infoHashV1: String,
        val originalName: String,
        /** Deliberately avoids leaking private torrent names as document names. */
        val suggestedFilename: String,
        val bytes: ByteArray
    )

    fun prepare(
        source: SnapshotManifestV1.Verified,
        infoHashV1: String
    ): Prepared {
        require(hashPattern.matches(infoHashV1)) { "Invalid v1 torrent identity" }
        val item = source.manifest.items.singleOrNull { it.infoHashV1 == infoHashV1 }
            ?: throw IllegalArgumentException("Torrent is absent from this archive")
        require(item.recovery == SnapshotManifestV1.Recovery.METAINFO) {
            "This item has no complete original torrent definition"
        }
        val original = source.metainfoByHash[infoHashV1]
            ?: throw IllegalArgumentException("Verified torrent definition missing")
        require(original.isNotEmpty() && original.size <= MAX_TORRENT_BYTES) {
            "Torrent definition is empty or too large"
        }
        val frozen = original.copyOf()
        val parsed = TorrentMetainfoReader.preview(frozen)
        require(parsed.infoHashV1 == infoHashV1 && parsed.name == item.originalName) {
            "Torrent name or infohash differs from the verified snapshot"
        }
        return Prepared(
            infoHashV1,
            item.originalName,
            "chunks-" + infoHashV1.take(12) + ".torrent",
            frozen
        )
    }
}
