package io.github.gamesbyian.chunks.engine

import org.junit.Assert.*
import org.junit.Test

class LocalFileBatchPlannerTest {
    private val current = listOf(
        LocalTransferFile(0, "Season/ep01.mkv", 50, 0, true),
        LocalTransferFile(1, "Season/ep02.mkv", 50, 10, false),
        LocalTransferFile(3, "notes.txt", 2, 1, true)
    )

    @Test fun validatesWholePageBeforeSelectingOnlyChangedPriorities() {
        val wanted = LocalFileBatchPlanner.prepare(current, current, true)
        assertEquals(listOf(1), wanted?.indicesToChange)
        assertEquals(3, wanted?.requestedCount)
        assertEquals(listOf(0, 3),
            LocalFileBatchPlanner.prepare(current, current, false)?.indicesToChange)
        assertTrue(LocalFileBatchPlanner.prepare(current, listOf(current[0]), true)
            ?.indicesToChange?.isEmpty() == true)
    }

    @Test fun changingProgressDoesNotInvalidateOtherwiseStableFileIdentity() {
        val old = current[0].copy(doneBytes = 0)
        val new = current.map { if (it.index == 0) it.copy(doneBytes = 25) else it }
        assertEquals(listOf(0),
            LocalFileBatchPlanner.prepare(new, listOf(old), false)?.indicesToChange)
    }

    @Test fun stalePathSizeWantedOrMissingIdentityRefusesEntireBatch() {
        val mismatches = listOf(
            current[0].copy(path = "other.mkv"),
            current[0].copy(sizeBytes = 51),
            current[0].copy(wanted = false),
            current[0].copy(index = 8)
        )
        for (bad in mismatches) {
            assertNull(LocalFileBatchPlanner.prepare(current,
                listOf(bad, current[1]), false))
        }
        assertNull(LocalFileBatchPlanner.prepare(current, listOf(current[0], current[0]), false))
        assertNull(LocalFileBatchPlanner.prepare(current, emptyList(), false))
        assertNull(LocalFileBatchPlanner.prepare(current, List(41) { current[0] }, false))
        assertNull(LocalFileBatchPlanner.prepare(current + current[0], listOf(current[1]), false))
    }
}
