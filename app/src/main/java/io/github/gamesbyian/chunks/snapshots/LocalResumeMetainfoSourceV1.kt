package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.Instant
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.Vectors
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent

/**
 * Bounded, read-only v1 metainfo recovery from Chunks' already-persisted native
 * resume records. This is NOT a fresh save_resume_data command and never
 * requests a libtorrent session mutation, opens payload paths, discovers peers
 * or changes a source torrent. Must run on an IO worker (JNI, disk).
 *
 * SnapshotInventoryCaptureV1 provides the authoritative fresh transfer list.
 * Recovered bytes are never trusted on the basis of their filename: the exact
 * original v1 SHA-1 infohash AND torrent name must match that fresh observation,
 * with SnapshotManifestV1 independently re-verifying the assembled archive.
 */
internal object LocalResumeMetainfoSourceV1 {
    private const val MAX_RESUME = 4L * 1024 * 1024
    private const val MAX_META = 2 * 1024 * 1024
    private val identity = Regex("[a-f0-9]{40}")

    enum class Unavailable {
        MISSING_OR_UNSAFE_RECORD, INVALID_RESUME, MISSING_NATIVE_INFO,
        OVERSIZED_OR_MISMATCHED_TORRENT
    }

    data class Recovery(
        val manifest: SnapshotManifestV1.Manifest,
        val sourceCount: Int,
        val omittedCount: Int,
        val metainfoByHash: Map<String, ByteArray>,
        val unavailableByHash: Map<String, Unavailable>
    ) {
        val verifiedRestorableCount: Int get() = metainfoByHash.size
        val inventoryOnlyCount: Int get() = manifest.items.size - metainfoByHash.size
        fun toArchive(): ByteArray =
            SnapshotManifestV1.encode(manifest, metainfoByHash)
    }

    fun capture(
        root: File,
        transfers: List<LocalTransferStatus>,
        createdAt: Instant,
        includeHistoricalDirectories: Boolean = false
    ): Recovery {
        val inventory = SnapshotInventoryCaptureV1.local(
            transfers, createdAt, includeHistoricalDirectories)
        val safeRoot = try {
            root.isDirectory && !Files.isSymbolicLink(root.toPath()) &&
                Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) { false }
        val metadata = LinkedHashMap<String, ByteArray>()
        val failures = LinkedHashMap<String, Unavailable>()
        val upgraded = inventory.manifest.items.map { item ->
            val hash = item.infoHashV1
            val bytes = if (!safeRoot || !identity.matches(hash)) {
                failures[hash] = Unavailable.MISSING_OR_UNSAFE_RECORD
                null
            } else {
                val file = File(root, "$hash.resume")
                val validFile = try {
                    Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isSymbolicLink(file.toPath()) &&
                        file.length() in 1..MAX_RESUME
                } catch (_: Exception) { false }
                if (!validFile) {
                    failures[hash] = Unavailable.MISSING_OR_UNSAFE_RECORD
                    null
                } else try {
                    // A source file could change during our bounded read.
                    // Any malformed/truncated JNI decode fails this row closed.
                    val packed = file.readBytes().takeIf { it.size in 1..MAX_RESUME.toInt() }
                    if (packed == null) {
                        failures[hash] = Unavailable.MISSING_OR_UNSAFE_RECORD
                        null
                    } else {
                        val ec = error_code()
                        val raw = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(packed), ec)
                        if (raw == null || ec.value() != 0) {
                            failures[hash] = Unavailable.INVALID_RESUME
                            null
                        } else {
                            val params = AddTorrentParams(raw)
                            val info = params.torrentInfo
                            if (info == null || !info.isValid) {
                                failures[hash] = Unavailable.MISSING_NATIVE_INFO
                                null
                            } else {
                                val torrent = Vectors.byte_vector2bytes(
                                    libtorrent.write_torrent_file_buf_ex(raw))
                                if (torrent.isEmpty() || torrent.size > MAX_META) {
                                    failures[hash] = Unavailable.OVERSIZED_OR_MISMATCHED_TORRENT
                                    null
                                } else {
                                    // Reject non-v1, hybrid, forged infohash, or renamed titles.
                                    val preview = TorrentMetainfoReader.preview(torrent)
                                    if (preview.infoHashV1 != hash || preview.name != item.originalName) {
                                        failures[hash] = Unavailable.OVERSIZED_OR_MISMATCHED_TORRENT
                                        null
                                    } else torrent
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    failures[hash] = Unavailable.INVALID_RESUME
                    null
                }
            }
            if (bytes == null) item else {
                metadata[hash] = bytes.copyOf()
                item.copy(recovery = SnapshotManifestV1.Recovery.METAINFO)
            }
        }
        val upgradedManifest = inventory.manifest.copy(items = upgraded)
        // The archive's higher-level schema enforces identical metainfo set,
        // name and exact hash before any result is exposed as verified.
        SnapshotManifestV1.encode(upgradedManifest, metadata)
        return Recovery(upgradedManifest, inventory.sourceCount, inventory.omitted.size,
            metadata, failures)
    }
}
