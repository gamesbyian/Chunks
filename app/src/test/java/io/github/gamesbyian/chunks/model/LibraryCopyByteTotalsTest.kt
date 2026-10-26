package io.github.gamesbyian.chunks.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryCopyByteTotalsTest {
    private fun file(bytes: Long) = LibraryCopyFile(File("unused"), "x", bytes)

    @Test fun ordinaryAndZeroLengthFilesSumExactly() {
        assertEquals(0L, LibraryCopyByteTotals.checked(emptyList()))
        assertEquals(30L, LibraryCopyByteTotals.checked(listOf(file(0L), file(10L), file(20L))))
        assertEquals(Long.MAX_VALUE, LibraryCopyByteTotals.checked(listOf(file(Long.MAX_VALUE), file(0L))))
    }

    @Test fun sumAboveSignedLongMaximumNeverWrapsOrLooksReclaimable() {
        assertNull(LibraryCopyByteTotals.checked(listOf(file(Long.MAX_VALUE), file(1L))))
        assertNull(LibraryCopyByteTotals.checked(listOf(file(Long.MAX_VALUE / 2 + 1), file(Long.MAX_VALUE / 2 + 1))))
        assertNull(LibraryCopyByteTotals.checked(listOf(file(1L), file(Long.MAX_VALUE))))
    }

    @Test fun invalidPerFileSizeIsRejected() {
        assertNull(LibraryCopyByteTotals.checked(listOf(file(1L), file(-1L))))
        assertNull(LibraryCopyByteTotals.checked(listOf(file(-1L))))
    }
}
