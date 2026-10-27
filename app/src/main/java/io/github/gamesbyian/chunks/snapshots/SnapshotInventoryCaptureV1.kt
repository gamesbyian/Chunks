package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.time.Instant
import java.util.Locale

/**
 * Read-only, capability-honest snapshot capture from existing immutable list
 * observations. No RPC, libtorrent command, path mutation or disk I/O happens
 * here. Raw .torrent retrieval is NOT implied by a name and an infohash.
 */
internal object SnapshotInventoryCaptureV1 {
    private const val MAX_INPUTS = 512
    private val v1 = Regex("[0-9a-f]{40}")

    enum class OmissionReason { UNSUPPORTED_IDENTITY, UNSAFE_NAME }

    data class Omitted(val identity: String, val reason: OmissionReason)

    data class Capture(
        val manifest: SnapshotManifestV1.Manifest,
        val sourceCount: Int,
        val omitted: List<Omitted>,
        val omittedDirectoryHints: Int
    ) {
        val inventoriedCount: Int get() = manifest.items.size
        /** Metadata has not been retrieved; none can be represented as restorable. */
        val verifiedRestorableCount: Int get() = 0

        fun toArchive(): ByteArray = SnapshotManifestV1.encode(manifest, emptyMap())
    }

    /** Server RPC inventory does not provide raw metainfo on every host. */
    fun remote(
        transfers: List<RemoteTransfer>,
        createdAt: Instant,
        includeHistoricalDirectories: Boolean = false
    ): Capture = capture(
        source = SnapshotManifestV1.Source.RTORRENT,
        entries = transfers.map { Row(it.hash, it.name, it.directory) },
        createdAt = createdAt,
        includeHistoricalDirectories = includeHistoricalDirectories
    )

    /** Local status metadata alone also does not prove raw metainfo recoverability. */
    fun local(
        transfers: List<LocalTransferStatus>,
        createdAt: Instant,
        includeHistoricalDirectories: Boolean = false
    ): Capture = capture(
        source = SnapshotManifestV1.Source.LOCAL,
        entries = transfers.map { Row(it.infoHash, it.name, it.savePath) },
        createdAt = createdAt,
        includeHistoricalDirectories = includeHistoricalDirectories
    )

    private data class Row(val hash: String, val name: String, val directory: String)

    private fun capture(
        source: SnapshotManifestV1.Source,
        entries: List<Row>,
        createdAt: Instant,
        includeHistoricalDirectories: Boolean
    ): Capture {
        require(entries.size <= MAX_INPUTS) {
            "Too many transfers for a single bounded snapshot: split the selection"
        }
        val seen = HashSet<String>()
        val items = ArrayList<SnapshotManifestV1.Item>()
        val omissions = ArrayList<Omitted>()
        var omittedHints = 0
        for (entry in entries) {
            val hash = entry.hash.lowercase(Locale.ROOT)
            if (!v1.matches(hash)) {
                omissions += Omitted(entry.hash.take(64), OmissionReason.UNSUPPORTED_IDENTITY)
                continue
            }
            require(seen.add(hash)) { "Duplicate infohash in source inventory" }
            // A display-only name can contain Unicode, but no controls or
            // unpaired UTF-16 surrogates. Do not truncate to invent identity.
            val validName = entry.name.length in 1..1024 &&
                entry.name != "." && entry.name != ".." &&
                entry.name.none { it.code < 32 || it.code == 127 } &&
                wellFormedUnicode(entry.name)
            if (!validName) {
                omissions += Omitted(hash, OmissionReason.UNSAFE_NAME)
                continue
            }
            val directory = if (includeHistoricalDirectories) {
                entry.directory.takeIf { value ->
                    value.length in 1..4096 &&
                        value.none { it.code < 32 || it.code == 127 } &&
                        wellFormedUnicode(value)
                }.also { if (it == null) omittedHints++ }
            } else null
            items += SnapshotManifestV1.Item(
                infoHashV1 = hash,
                originalName = entry.name,
                recovery = SnapshotManifestV1.Recovery.INVENTORY,
                sourceDirectoryHint = directory
            )
        }
        val manifest = SnapshotManifestV1.Manifest(
            producer = "chunks-android",
            createdAtUtc = createdAt.toString(),
            source = source,
            items = items
        )
        return Capture(manifest, entries.size, omissions, omittedHints)
    }

    private fun wellFormedUnicode(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            when {
                value[i].isLowSurrogate() -> return false
                value[i].isHighSurrogate() -> {
                    i++
                    if (i >= value.length || !value[i].isLowSurrogate()) return false
                }
            }
            i++
        }
        return true
    }
}
