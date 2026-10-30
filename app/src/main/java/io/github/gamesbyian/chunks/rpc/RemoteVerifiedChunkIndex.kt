package io.github.gamesbyian.chunks.rpc

/**
 * Read-only prefix index for rTorrent's most-significant-bit-first hex piece
 * bitfield. At most 10 million pieces, with O(pieces / 4) indexing and O(1)
 * completion queries even when many file ranges overlap.
 *
 * Only valid pieces are counted: low unused bits in the final nibble cannot
 * falsely prove a partially filled torrent complete.
 */
internal class RemoteVerifiedChunkIndex(private val hex: String, private val pieceCount: Long) {
    private val prefix: IntArray

    init {
        require(pieceCount in 0L..10_000_000L) { "Invalid chunk count" }
        require(hex.length.toLong() * 4 >= pieceCount) { "Incomplete chunk bitfield" }
        val nibbleCount = ((pieceCount + 3) / 4).toInt()
        prefix = IntArray(nibbleCount + 1)
        for (nibble in 0 until nibbleCount) {
            val value = Character.digit(hex[nibble], 16)
            require(value >= 0) { "Invalid chunk bitfield digit" }
            val validBits = minOf(4L, pieceCount - nibble.toLong() * 4).toInt()
            val mask = (0xF shl (4 - validBits)) and 0xF
            prefix[nibble + 1] = prefix[nibble] + Integer.bitCount(value and mask)
        }
    }

    private fun completedBefore(index: Long): Int {
        val nibble = (index / 4).toInt()
        var total = prefix[nibble]
        val tailBits = (index % 4).toInt()
        if (tailBits != 0) {
            val value = Character.digit(hex[nibble], 16)
            val mask = (0xF shl (4 - tailBits)) and 0xF
            total += Integer.bitCount(value and mask)
        }
        return total
    }

    fun completedIn(first: Long, exclusiveEnd: Long): Long {
        require(first in 0..pieceCount && exclusiveEnd in first..pieceCount) {
            "Invalid file chunk range"
        }
        return (completedBefore(exclusiveEnd) - completedBefore(first)).toLong()
    }
}
