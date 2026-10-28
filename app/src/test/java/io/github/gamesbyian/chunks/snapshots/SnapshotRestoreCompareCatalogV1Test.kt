package io.github.gamesbyian.chunks.snapshots

import org.junit.Assert.*
import org.junit.Test

class SnapshotRestoreCompareCatalogV1Test {
    private val entries = (0 until 67).map { index ->
        SnapshotRestorePreviewV1.Entry(
            infoHashV1 = index.toString(16).padStart(40, '0'),
            originalName = if (index % 2 == 0) "Space episode $index" else "Music archive $index",
            decision = when (index % 5) {
                0 -> SnapshotRestorePreviewV1.Decision.ALREADY_PRESENT
                1 -> SnapshotRestorePreviewV1.Decision.METADATA_NEEDS_REVIEW
                2 -> SnapshotRestorePreviewV1.Decision.MAGNET_NEEDS_REVIEW
                3 -> SnapshotRestorePreviewV1.Decision.INVENTORY_ONLY
                else -> SnapshotRestorePreviewV1.Decision.SOURCE_FAILED
            },
            selectedForReview = index % 5 == 1 || index % 5 == 2,
            historicalDirectoryHint = null
        )
    }

    @Test fun allEntriesAreReachableWithoutTruncation() {
        val pages = (0..3).map {
            SnapshotRestoreCompareCatalogV1.select(entries, "", null, it)
        }
        assertEquals(listOf(20, 20, 20, 7), pages.map { it.entries.size })
        assertEquals(entries.map { it.infoHashV1 },
            pages.flatMap { it.entries }.map { it.infoHashV1 })
        assertFalse(pages.first().hasPrevious)
        assertTrue(pages.first().hasNext)
        assertTrue(pages.last().hasPrevious)
        assertFalse(pages.last().hasNext)
        assertEquals(61, pages.last().first)
        assertEquals(67, pages.last().last)
    }

    @Test fun filtersAndSearchKeepHonestCountsAndReachablePages() {
        val metadata = SnapshotRestoreCompareCatalogV1.select(
            entries, "", SnapshotRestorePreviewV1.Decision.METADATA_NEEDS_REVIEW, 0)
        assertEquals(14, metadata.matches)
        assertTrue(metadata.entries.all {
            it.decision == SnapshotRestorePreviewV1.Decision.METADATA_NEEDS_REVIEW
        })
        val names = SnapshotRestoreCompareCatalogV1.select(entries, "  SPACE EPISODE ", null, 0)
        assertEquals(34, names.matches)
        val matchedHash = entries[45].infoHashV1
        val hash = SnapshotRestoreCompareCatalogV1.select(entries, matchedHash, null, 0)
        assertEquals(1, hash.matches)
        assertEquals(matchedHash, hash.entries.single().infoHashV1)
        val empty = SnapshotRestoreCompareCatalogV1.select(entries, "no-such-record", null, 999)
        assertEquals(0, empty.matches)
        assertEquals(0, empty.first)
        assertEquals(0, empty.last)
        assertFalse(empty.hasNext)
        val end = SnapshotRestoreCompareCatalogV1.select(entries, "", null, Int.MAX_VALUE)
        assertEquals(3, end.page)
        assertEquals(7, end.entries.size)
    }

    @Test fun oversizedCatalogIsRejectedBeforeRendering() {
        try {
            SnapshotRestoreCompareCatalogV1.select(List(513) { entries[0] }, "", null, 0)
            fail("Unsafe >512-entry input was accepted")
        } catch (_: IllegalArgumentException) { }
    }
}
