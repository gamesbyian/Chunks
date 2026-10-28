package io.github.gamesbyian.chunks.model

import java.util.Locale

/**
 * An explicitly reviewed metadata-ID handoff. This file is NOT a torrent
 * payload, raw .torrent backup, resume file or cross-engine Move.
 *
 * No tracker, passkey, server path, account, credential or private announce
 * URL is ever copied. Private/tracker-only torrents may be unusable.
 */
internal object BatchMagnetHandoff {
    const val MAX_ITEMS = 512
    const val MAX_BYTES = 256 * 1024
    private val v1 = Regex("[0-9a-fA-F]{40}")

    data class Row(val infoHash: String, val originalName: String)
    data class Prepared(
        val bytes: ByteArray,
        val selected: Int,
        val exported: Int,
        val unsupported: Int,
        val containsTitles: Boolean
    )

    /**
     * Fail closed if the selected list contains an absent identity (stale view)
     * or duplicate identity (not one exact target per transfer). Unsupported
     * v2/hybrid-only identities are counted, never represented as v1 magnets.
     */
    fun prepare(
        available: List<Row>,
        selectedHashes: Collection<String>,
        includeOriginalTitles: Boolean
    ): Prepared? {
        if (selectedHashes.isEmpty() || selectedHashes.size > MAX_ITEMS) return null
        val selected = selectedHashes.map { it.lowercase(Locale.ROOT) }
        if (selected.toSet().size != selected.size) return null
        val selectedSet = selected.toSet()
        val matches = available.filter { it.infoHash.lowercase(Locale.ROOT) in selectedSet }
        if (matches.size != selectedSet.size) return null
        if (matches.map { it.infoHash.lowercase(Locale.ROOT) }.toSet().size != matches.size)
            return null
        val magnets = ArrayList<String>()
        var unsupported = 0
        for (row in matches) {
            if (!v1.matches(row.infoHash)) {
                unsupported++
                continue
            }
            val magnet = TorrentIdentityHandoff.prepare(
                row.infoHash, if (includeOriginalTitles) row.originalName else "")
                ?: return null
            // A single magnet is one line, without embedded whitespace controls
            // or external tracker URLs; it is never executable.
            if (magnet.any { it == '\n' || it == '\r' }) return null
            magnets += magnet
        }
        if (magnets.isEmpty()) return null
        val bytes = (magnets.joinToString("\n", postfix = "\n")).toByteArray(Charsets.UTF_8)
        if (bytes.size !in 1..MAX_BYTES) return null
        return Prepared(bytes, selected.size, magnets.size, unsupported, includeOriginalTitles)
    }
}
