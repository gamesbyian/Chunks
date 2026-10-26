package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalTransferFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class LocalLibraryCopySelectionTest {
    private fun file(index: Int, complete: Boolean = true, wanted: Boolean = true) =
        LocalTransferFile(index, "Series/file-$index.bin", 128L,
            if (complete) 128L else 64L, wanted)

    @Test fun refusesAnExtraRowRatherThanPublishingOnlyTheFirstTenThousand() {
        val inventory = (0 until LocalLibraryCopySelection.PROBE_LIMIT).map(::file)
        assertNull(LocalLibraryCopySelection.completeWanted(inventory))
    }

    @Test fun acceptsExactlyTheBoundWhenEveryWantedFileIsFinished() {
        val inventory = (0 until LocalLibraryCopySelection.MAX_FILES).map(::file)
        val wanted = LocalLibraryCopySelection.completeWanted(inventory)
        assertNotNull(wanted)
        assertEquals(LocalLibraryCopySelection.MAX_FILES, wanted!!.size)
    }

    @Test fun selectedIncompleteFilesBlockCopyButUnselectedFilesDoNot() {
        assertNull(LocalLibraryCopySelection.completeWanted(
            listOf(file(0), file(1, complete = false))))
        val wanted = LocalLibraryCopySelection.completeWanted(
            listOf(file(0), file(1, complete = false, wanted = false)))
        assertEquals(listOf(0), wanted!!.map { it.index })
    }

    @Test fun emptyOrAllSkippedSelectionCannotBePublished() {
        assertNull(LocalLibraryCopySelection.completeWanted(emptyList()))
        assertNull(LocalLibraryCopySelection.completeWanted(listOf(file(0, wanted = false))))
    }
}
