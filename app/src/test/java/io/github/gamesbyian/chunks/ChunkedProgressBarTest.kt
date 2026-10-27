package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Test

class ChunkedProgressBarTest {
    @Test fun partialChunkRemainsAccurateRatherThanRoundingUp() {
        val progress = 0.31f
        val values = (0 until 12).map { chunkFillFraction(progress, it, 12) }
        assertEquals(1f, values[0], 0f)
        assertEquals(1f, values[1], 0f)
        assertEquals(1f, values[2], 0f)
        assertEquals(0.72f, values[3], 0.0001f)
        assertEquals(0f, values[4], 0f)
        assertEquals(progress * 12, values.sum(), 0.0001f)
    }

    @Test fun zeroAndFullFillAllChunksExactly() {
        for (index in 0 until 12) {
            assertEquals(0f, chunkFillFraction(0f, index, 12), 0f)
            assertEquals(1f, chunkFillFraction(1f, index, 12), 0f)
        }
    }

    @Test fun invalidOrOutOfRangeProgressDoesNotProduceFalseCompletion() {
        assertEquals(0f, safeProgressFraction(Float.NaN), 0f)
        assertEquals(0f, safeProgressFraction(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, safeProgressFraction(-2f), 0f)
        assertEquals(1f, safeProgressFraction(2f), 0f)
        assertEquals(0f, chunkFillFraction(Float.NaN, 0, 12), 0f)
    }

    @Test fun supportsSingleChunkWithoutRounding() {
        assertEquals(0.375f, chunkFillFraction(0.375f, 0, 1), 0.0001f)
    }
}
