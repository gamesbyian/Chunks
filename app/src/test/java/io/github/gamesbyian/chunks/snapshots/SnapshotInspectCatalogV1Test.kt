package io.github.gamesbyian.chunks.snapshots

import org.junit.Assert.*
import org.junit.Test

class SnapshotInspectCatalogV1Test {
    private val samples = List(55) { index ->
        SnapshotManifestV1.Item(
            infoHashV1 = index.toString(16).padStart(40, '0'),
            originalName = if (index % 2 == 0)
                "Space Show episode " + index else "Music collection " + index,
            recovery = when (index % 4) {
                0 -> SnapshotManifestV1.Recovery.METAINFO
                1 -> SnapshotManifestV1.Recovery.MAGNET
                2 -> SnapshotManifestV1.Recovery.INVENTORY
                else -> SnapshotManifestV1.Recovery.FAILED
            }
        )
    }

    @Test fun paginationReachesEveryRecordRatherThanSilentlyHidingAfterThirty() {
        val a = SnapshotInspectCatalogV1.select(samples, "", null, 0)
        val b = SnapshotInspectCatalogV1.select(samples, "", null, 1)
        val c = SnapshotInspectCatalogV1.select(samples, "", null, 2)
        assertEquals(20, a.items.size)
        assertEquals(20, b.items.size)
        assertEquals(15, c.items.size)
        assertFalse(a.hasPrevious)
        assertTrue(a.hasNext)
        assertTrue(b.hasPrevious)
        assertFalse(c.hasNext)
        assertEquals(41, c.firstIndex)
        assertEquals(55, c.lastIndex)
        assertEquals(samples.map { it.infoHashV1 },
            (a.items + b.items + c.items).map { it.infoHashV1 })
    }

    @Test fun searchMatchesNamesCaseInsensitivelyAndExactInfohashText() {
        val nameMatches = SnapshotInspectCatalogV1.select(samples, "  SPACE show ", null, 0)
        assertEquals(28, nameMatches.matchingCount)
        assertTrue(nameMatches.items.all { it.originalName.startsWith("Space Show") })
        val hash = samples[34].infoHashV1
        val found = SnapshotInspectCatalogV1.select(samples, hash, null, 0)
        assertEquals(1, found.matchingCount)
        assertEquals(hash, found.items.single().infoHashV1)
    }

    @Test fun statusFilterAndPagingClampRefuseFalseCounts() {
        val metainfo = SnapshotInspectCatalogV1.select(
            samples, "", SnapshotManifestV1.Recovery.METAINFO, 0)
        assertEquals(14, metainfo.matchingCount)
        assertTrue(metainfo.items.all {
            it.recovery == SnapshotManifestV1.Recovery.METAINFO
        })
        val noMatch = SnapshotInspectCatalogV1.select(
            samples, "nothing-found", null, Int.MAX_VALUE)
        assertEquals(0, noMatch.matchingCount)
        assertEquals(0, noMatch.firstIndex)
        assertEquals(0, noMatch.lastIndex)
        assertFalse(noMatch.hasNext)
        assertFalse(noMatch.hasPrevious)
        val clamped = SnapshotInspectCatalogV1.select(samples, "", null, Int.MAX_VALUE)
        assertEquals(2, clamped.pageIndex)
        assertEquals(15, clamped.items.size)
    }

    @Test fun rejectsOversizedUntrustedCatalog() {
        try {
            SnapshotInspectCatalogV1.select(List(513) { samples[0] }, "", null, 0)
            fail("Must reject a catalog larger than the v1 validator allows")
        } catch (_: IllegalArgumentException) { }
    }
}
