package io.github.gamesbyian.chunks.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure layout arithmetic for the restore-time saved-piece versus on-disk length check. */
class LocalRestoredPayloadShortfallTest {
    // Three files over 10-byte pieces: [0,25) [25,25+5 pad) [30,52).
    private val layout = LocalRestoredPayloadShortfall.Layout(
        pieceLength = 10, numPieces = 6,
        fileOffsets = longArrayOf(0, 25, 30), fileSizes = longArrayOf(25, 5, 22),
        padFiles = booleanArrayOf(false, true, false))

    private fun short(have: Set<Int>, lengths: Map<Int, Long?>) =
        LocalRestoredPayloadShortfall.firstShortFile(layout, { it in have }) { lengths[it] }

    @Test fun completeIntactFilesPass() =
        assertNull(short((0..5).toSet(), mapOf(0 to 25L, 2 to 22L)))

    @Test fun noClaimedPiecesNeverRequiresAFile() =
        assertNull(short(emptySet(), emptyMap()))

    @Test fun truncatedCompleteFileIsShort() =
        assertEquals(2, short((0..5).toSet(), mapOf(0 to 25L, 2 to 21L)))

    @Test fun missingFileWithClaimedPiecesIsShort() =
        assertEquals(0, short(setOf(0), mapOf(2 to 22L)))

    @Test fun partialClaimNeedsOnlyBytesUpToTheLastClaimedPiece() {
        // Piece 1 ends at byte 20 of file 0; a sparse/partial 20-byte file satisfies it.
        assertNull(short(setOf(0, 1), mapOf(0 to 20L)))
        assertEquals(0, short(setOf(0, 1), mapOf(0 to 19L)))
    }

    @Test fun pieceSpanningTwoFilesClaimsTheTailOfTheFirst() {
        // Piece 2 covers file 0 bytes [20,25) and the pad file; file 0 must be whole.
        assertEquals(0, short(setOf(2), mapOf(0 to 24L)))
        assertNull(short(setOf(2), mapOf(0 to 25L)))
    }

    @Test fun padFilesAreNeverStatted() =
        assertNull(short(setOf(2), mapOf(0 to 25L, 1 to null)))

    @Test fun lastShortPieceUsesTheFileEndNotAWholePiece() {
        // Piece 5 covers [50,52): file 2's claimed end is its full 22 bytes, not 30.
        assertNull(short(setOf(5), mapOf(2 to 22L)))
    }
}
