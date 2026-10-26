package io.github.gamesbyian.chunks.model

import org.junit.Assert.*
import org.junit.Test

class ManualCollectionPagerTest {
    @Test fun everyTransferInLargeViewIsReachableExactlyOnce() {
        val source = (0 until 437).toList()
        val pages = (0 until 11).map { ManualCollectionPager.slice(source, it) }
        assertEquals(11, pages.first().totalPages)
        assertEquals(437, pages.first().totalMatches)
        assertEquals(source, pages.flatMap { it.items })
        assertEquals(40, pages.first().items.size)
        assertEquals(37, pages.last().items.size)
        assertTrue(pages.first().hasNext)
        assertFalse(pages.first().hasPrevious)
        assertTrue(pages.last().hasPrevious)
        assertFalse(pages.last().hasNext)
    }

    @Test fun emptyAndChangedSearchesClampRatherThanInventingRows() {
        val empty = ManualCollectionPager.slice(emptyList<Int>(), 99)
        assertEquals(0, empty.totalMatches)
        assertEquals(0, empty.pageIndex)
        assertEquals(1, empty.totalPages)
        assertTrue(empty.items.isEmpty())
        assertEquals((80 until 85).toList(),
            ManualCollectionPager.slice((0 until 85).toList(), Int.MAX_VALUE).items)
        assertEquals((0 until 40).toList(),
            ManualCollectionPager.slice((0 until 85).toList(), -100).items)
    }

    @Test fun rejectsInvalidPageSizes() {
        try {
            ManualCollectionPager.slice(listOf(1), 0, 0)
            fail("Zero-sized pages cannot be used")
        } catch (_: IllegalArgumentException) {}
    }
}
