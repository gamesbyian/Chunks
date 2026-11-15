package io.github.gamesbyian.chunks.engine

/**
 * libtorrent 2.0 restores a job's saved verified-piece bitfield WITHOUT
 * checking that the files still hold those bytes (it no longer compares file
 * sizes or mtimes on resume). A payload truncated, replaced by a shorter file
 * or deleted while Chunks was not running therefore restores as complete and
 * verified. This read-only check finds the first file whose saved pieces end
 * beyond its current on-disk length, so the caller can ask libtorrent itself
 * to re-hash the stopped job. It never reads payload bytes, never writes and
 * never decides which pieces are good; equal-size corruption is out of scope
 * (as for every ordinary client) and remains for an explicit Recheck.
 */
internal object LocalRestoredPayloadShortfall {
    class Layout(
        val pieceLength: Int,
        val numPieces: Int,
        val fileOffsets: LongArray,
        val fileSizes: LongArray,
        val padFiles: BooleanArray
    )

    /**
     * @param havePiece the restored (resume-claimed) verified bitfield.
     * @param onDiskLength current regular-file length, or null when the file is
     *   missing or is not a regular file.
     * @return the first file index whose claimed bytes are not on disk, or null.
     */
    fun firstShortFile(layout: Layout, havePiece: (Int) -> Boolean, onDiskLength: (Int) -> Long?): Int? {
        val pieceLength = layout.pieceLength.toLong()
        if (pieceLength <= 0 || layout.numPieces <= 0) return null
        for (index in layout.fileSizes.indices) {
            val size = layout.fileSizes[index]
            if (layout.padFiles[index] || size <= 0) continue
            val start = layout.fileOffsets[index]
            val end = start + size
            val first = (start / pieceLength).toInt()
            val last = ((end - 1) / pieceLength).toInt().coerceAtMost(layout.numPieces - 1)
            var claimedPiece = -1
            for (piece in last downTo first) if (havePiece(piece)) { claimedPiece = piece; break }
            if (claimedPiece < 0) continue
            val claimedEnd = minOf((claimedPiece + 1) * pieceLength, end) - start
            val actual = onDiskLength(index) ?: return index
            if (actual < claimedEnd) return index
        }
        return null
    }
}
