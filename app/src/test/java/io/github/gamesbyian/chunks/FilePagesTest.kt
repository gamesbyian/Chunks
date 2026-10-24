package io.github.gamesbyian.chunks

import org.junit.Assert.*
import org.junit.Test

class FilePagesTest {
    @Test fun emptyAndSingleFileAreSingleBoundedPage() {
        assertEquals(FilePage(0, 1, 0, 0), FilePages.select(0, 100))
        assertEquals(FilePage(0, 1, 0, 1), FilePages.select(1, -100))
        assertEquals(FilePage(0, 1, 0, 40), FilePages.select(40, 3))
    }

    @Test fun pagesCoverAllIndicesOnceWithoutOverflow() {
        for (size in listOf(41, 79, 80, 81, 250, 12000)) {
            val first = FilePages.select(size, -999)
            val last = FilePages.select(size, Int.MAX_VALUE)
            assertEquals(0, first.firstIndex)
            assertEquals(size, last.endExclusive)
            val collected = (0 until first.count).flatMap { page ->
                val item = FilePages.select(size, page)
                (item.firstIndex until item.endExclusive).toList()
            }
            assertEquals((0 until size).toList(), collected)
        }
    }

    @Test fun absurdFileCountsAreRejectedRatherThanRenderingUnboundedContent() {
        for (count in listOf(-1, 12001, Int.MAX_VALUE)) {
            try {
                FilePages.select(count, 0)
                fail("Invalid file count must be refused")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
