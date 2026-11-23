package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.rpc.TorrentMetainfoPreview

/** Portable priorities are exact choices. Unknown or lossy native values omit the whole layout. */
internal object SnapshotFilePriorities {
    const val MAX_FILES = 1_024
    const val MAX_TOTAL_FILES = 16_384

    enum class Level(val wire: Char, val local: Int, val rTorrent: Int?, val label: String) {
        SKIP('s', 0, 0, "Skip"),
        LOW('l', 1, null, "Low"),
        NORMAL('n', 4, 1, "Normal"),
        HIGH('h', 7, 2, "High")
    }

    data class File(val index: Int, val path: String, val sizeBytes: Long)
    data class Observation(val file: File, val priority: Int)
    data class Mapping(val file: File, val level: Level)

    fun layout(preview: TorrentMetainfoPreview): List<File> =
        if (preview.files.isEmpty()) listOf(File(0, preview.name, preview.sizeBytes))
        else preview.files.mapIndexedNotNull { index, file ->
            if (file.padding) null else File(index, file.path, file.sizeBytes)
        }

    fun capture(
        preview: TorrentMetainfoPreview,
        observed: List<Observation>?,
        source: SnapshotManifestV1.Source
    ): List<Level>? {
        val expected = layout(preview)
        if (observed == null || expected.size !in 1..MAX_FILES ||
            observed.size != expected.size) return null
        val rows = expected.zip(observed)
        // rTorrent's standard f.path is relative to the torrent root, while
        // native Local paths include it. Require a consistent layout convention.
        val full = rows.all { (file, row) -> row.file == file }
        val relative = source == SnapshotManifestV1.Source.RTORRENT && rows.all { (file, row) ->
            row.file == file.copy(path = file.path.substringAfter('/'))
        }
        if (!full && !relative) return null
        return observed.map { row ->
            Level.entries.singleOrNull {
                row.priority == if (source == SnapshotManifestV1.Source.LOCAL) it.local else it.rTorrent
            } ?: return null
        }
    }
}
