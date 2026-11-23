package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.rpc.RemoteFile
import io.github.gamesbyian.chunks.rpc.RemoteTransfer

/**
 * One read-only comparison against current files on an independently verified
 * exact rTorrent session. This object grants NO mutation/restore authority.
 */
internal class SnapshotRemotePriorityComparison private constructor(
    val rows: List<Row>
) {
    data class Row(
        val file: SnapshotFilePriorities.File,
        val saved: SnapshotFilePriorities.Level,
        val current: SnapshotFilePriorities.Level
    )

    val changeCount: Int get() = rows.count { it.saved != it.current }
    val unsupportedCount: Int get() = rows.count { it.saved.rTorrent == null }

    companion object {
        fun plan(
            entry: SnapshotRestorePreviewV1.Entry,
            observed: RemoteTransfer?,
            files: List<RemoteFile>?
        ): SnapshotRemotePriorityComparison? {
            if (entry.decision != SnapshotRestorePreviewV1.Decision.ALREADY_PRESENT ||
                observed == null || files == null ||
                !observed.hash.equals(entry.infoHashV1, ignoreCase = true) ||
                observed.name != entry.originalName ||
                entry.fileMappings.size !in 1..SnapshotFilePriorities.MAX_FILES ||
                files.size != entry.fileMappings.size) return null
            val mappings = entry.fileMappings
            if (mappings.map { it.file.index }.distinct().size != mappings.size ||
                mappings.any { it.file.index < 0 || it.file.sizeBytes < 0 }) return null

            // rTorrent's f.path is relative to the torrent's root on common
            // builds. Require one whole-list convention, not per-row guesses.
            val exact = mappings.zip(files).all { (saved, current) ->
                saved.file.path == current.path && saved.file.sizeBytes == current.sizeBytes
            }
            val relative = mappings.zip(files).all { (saved, current) ->
                val relativePath = SnapshotFilePriorities.relativeToTorrentRoot(
                    saved.file, entry.originalName)
                relativePath != null && relativePath == current.path &&
                    saved.file.sizeBytes == current.sizeBytes
            }
            if (!exact && !relative) return null
            val rows = mappings.zip(files).map { (saved, current) ->
                val level = SnapshotFilePriorities.Level.entries.singleOrNull {
                    it.rTorrent != null && it.rTorrent == current.priority
                } ?: return null
                Row(saved.file, saved.level, level)
            }
            return SnapshotRemotePriorityComparison(rows)
        }
    }
}
