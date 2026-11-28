package io.github.gamesbyian.chunks.engine

/** Whole v1 piece ranges, including unrelated boundary bytes. No native
 * handles, filesystem operations, destination authority or decoder. */
internal data class LocalArchivePiecePlan(
    val selected: List<LocalTransferFile>,
    val pieces: List<Piece>
) {
    data class Span(val file: LocalTransferFile, val offset: Long)
    data class Slice(val file: LocalTransferFile, val offset: Long, val length: Int)
    data class Piece(val index: Int, val sha1: String, val slices: List<Slice>)

    companion object {
        const val MAX_INPUT_BYTES = 8 * 1024 * 1024
        private const val MAX_READ_BYTES = 16 * 1024 * 1024
        private const val MAX_PIECES = 1024

        fun build(selected: List<LocalTransferFile>, layout: List<Span>, total: Long,
                  pieceLength: Int, pieceCount: Int,
                  verifiedHash: (Int) -> String?): LocalArchivePiecePlan? {
            if (selected.size !in 1..32 || layout.size !in 1..10_000 ||
                total <= 0 || pieceLength !in 1..MAX_INPUT_BYTES ||
                pieceCount <= 0 || (total - 1) / pieceLength + 1 != pieceCount.toLong() ||
                selected.any { !it.wanted || it.sizeBytes !in 1L..MAX_INPUT_BYTES.toLong() ||
                    it.doneBytes != it.sizeBytes } ||
                selected.sumOf { it.sizeBytes } > MAX_INPUT_BYTES ||
                selected.map { it.index }.distinct().size != selected.size ||
                layout.map { it.file.index }.distinct().size != layout.size ||
                layout.map { it.file.path }.distinct().size != layout.size) return null
            var cursor = 0L
            val byIndex = layout.associateBy { it.file.index }
            for ((file, offset) in layout) {
                if (file.sizeBytes < 0 || offset != cursor || file.sizeBytes > total - cursor) return null
                cursor += file.sizeBytes
            }
            if (cursor != total) return null
            val indices = sortedSetOf<Int>()
            for (file in selected) {
                val span = byIndex[file.index] ?: return null
                if (span.file.copy(doneBytes = file.doneBytes) != file) return null
                val first = (span.offset / pieceLength).toInt()
                val last = ((span.offset + file.sizeBytes - 1) / pieceLength).toInt()
                if (last - first >= MAX_PIECES) return null
                for (index in first..last) {
                    indices.add(index)
                    if (indices.size > MAX_PIECES) return null
                }
            }
            var readBytes = 0L
            var layoutIndex = 0
            val pieces = ArrayList<Piece>(indices.size)
            for (index in indices) {
                val start = index.toLong() * pieceLength
                val length = minOf(pieceLength.toLong(), total - start)
                val end = start + length
                readBytes += length
                if (readBytes > MAX_READ_BYTES) return null
                while (layoutIndex < layout.size &&
                    layout[layoutIndex].offset + layout[layoutIndex].file.sizeBytes <= start) layoutIndex++
                var scan = layoutIndex
                var covered = 0L
                val slices = mutableListOf<Slice>()
                while (scan < layout.size && layout[scan].offset < end) {
                    val span = layout[scan++]
                    val from = maxOf(start, span.offset)
                    val until = minOf(end, span.offset + span.file.sizeBytes)
                    if (until <= from) continue
                    if (!span.file.wanted) return null
                    slices += Slice(span.file, from - span.offset, (until - from).toInt())
                    covered += until - from
                }
                if (covered != length) return null
                val hash = verifiedHash(index)?.takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: return null
                pieces += Piece(index, hash, slices)
            }
            return LocalArchivePiecePlan(selected.toList(), pieces)
        }
    }
}
