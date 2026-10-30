package io.github.gamesbyian.chunks.rpc

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteVerifiedChunkIndexTest {
    @Test fun pieceRangesUseMostSignificantBitFirstAndHalfOpenBounds() {
        // A3 = 1010_0011: pieces 0, 2, 6, 7.
        val index = RemoteVerifiedChunkIndex("a3", 8)
        assertEquals(4L, index.completedIn(0, 8))
        assertEquals(1L, index.completedIn(0, 2))
        assertEquals(1L, index.completedIn(1, 4))
        assertEquals(2L, index.completedIn(4, 8))
        assertEquals(0L, index.completedIn(4, 4))
        assertEquals(1L, index.completedIn(6, 7))
    }

    @Test fun unusedTailBitsCannotCountAsVerifiedPieces() {
        assertEquals(3L, RemoteVerifiedChunkIndex("ff", 3).completedIn(0, 3))
        assertEquals(1L, RemoteVerifiedChunkIndex("0f", 5).completedIn(4, 5))
        assertEquals(0L, RemoteVerifiedChunkIndex("0f", 4).completedIn(0, 4))
        assertEquals(0L, RemoteVerifiedChunkIndex("", 0).completedIn(0, 0))
    }

    @Test fun invalidBoundsAndBitfieldsFailClosed() {
        fun rejected(block: () -> Unit) {
            assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)
        }
        rejected { RemoteVerifiedChunkIndex("0", 5) }
        rejected { RemoteVerifiedChunkIndex("z", 4) }
        rejected { RemoteVerifiedChunkIndex("0", -1) }
        val index = RemoteVerifiedChunkIndex("f0", 8)
        rejected { index.completedIn(-1, 1) }
        rejected { index.completedIn(5, 4) }
        rejected { index.completedIn(0, 9) }
    }

    @Test fun arbitraryOverlappingRangesAgreeWithIndependentPieceByPieceReference() {
        val random = Random(568)
        repeat(40) {
            val pieces = random.nextInt(1, 200)
            val nibbleCount = (pieces + 3) / 4
            val hex = buildString {
                repeat(nibbleCount) { append("0123456789aBcDeF"[random.nextInt(16)]) }
            }
            val index = RemoteVerifiedChunkIndex(hex, pieces.toLong())
            repeat(200) {
                val first = random.nextInt(0, pieces + 1)
                val end = random.nextInt(first, pieces + 1)
                val expected = (first until end).count { piece ->
                    val digit = Character.digit(hex[piece / 4], 16)
                    digit and (8 shr (piece % 4)) != 0
                }.toLong()
                assertEquals("range [$first,$end) in $hex", expected,
                    index.completedIn(first.toLong(), end.toLong()))
            }
        }
    }

    @Test fun largeOverlappingInventoryDoesNotRequireRepeatedPieceScans() {
        val pieces = 400_000L
        val index = RemoteVerifiedChunkIndex("f".repeat(100_000), pieces)
        repeat(12_000) { i ->
            assertEquals(pieces - i.toLong(),
                index.completedIn(i.toLong(), pieces))
        }
    }
}
