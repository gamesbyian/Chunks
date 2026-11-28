package io.github.gamesbyian.chunks.engine

/** Point-in-time native have-bits only. This is not downloaded block
 * progress, peer availability, a disk/cache witness or operation authority. */
internal data class LocalVerifiedPieceMap(
    val totalPieces: Int,
    val ranges: List<Range>
) {
    data class Range(val first: Int, val count: Int, val verified: Int)
    val verifiedPieces: Int get() = ranges.sumOf { it.verified }

    companion object {
        const val MAX_PIECES = 65_536
        const val MAX_RANGES = 64

        /** Exact contiguous counts, not sampling or chronological progress.
         * Reject oversize before invoking any bit reader. */
        fun observe(count: Int, hasPiece: (Int) -> Boolean): LocalVerifiedPieceMap? {
            if (count !in 1..MAX_PIECES) return null
            val width = (count + MAX_RANGES - 1) / MAX_RANGES
            val ranges = ArrayList<Range>(MAX_RANGES)
            var start = 0
            while (start < count) {
                if (Thread.currentThread().isInterrupted) return null
                val end = minOf(count, start + width)
                var verified = 0
                for (index in start until end) if (hasPiece(index)) verified++
                ranges += Range(start, end - start, verified)
                start = end
            }
            return LocalVerifiedPieceMap(count, ranges)
        }
    }
}
