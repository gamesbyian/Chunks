package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.*
import org.junit.Test

class TransferCatalogTest {
    private val examples = listOf(
        RemoteTransfer("A", "Star.Trek.S01E01.mkv", 100, 60, true, "/tv", 250, 10),
        RemoteTransfer("B", "Spider-Man.028.cbz", 80, 80, false, "/comics", 0, 5),
        RemoteTransfer("C", "obscure.xyz", 900, 10, true, "/unsorted", 100, 0)
    )

    @Test fun filtersSearchAndSortingAreStable() {
        assertEquals(2, TransferCatalog.visible(examples, "", TransferFilter.ACTIVE, TransferOrder.NAME).size)
        assertEquals("B", TransferCatalog.visible(examples, "", TransferFilter.COMPLETE, TransferOrder.NAME).single().hash)
        assertEquals("C", TransferCatalog.visible(examples, "", TransferFilter.NEEDS_SORTING, TransferOrder.NAME).single().hash)
        assertEquals("A", TransferCatalog.visible(examples, "/tv", TransferFilter.ALL, TransferOrder.NAME).single().hash)
        assertEquals("C", TransferCatalog.visible(examples, "", TransferFilter.ALL, TransferOrder.SIZE).first().hash)
        assertEquals("A", TransferCatalog.visible(examples, "", TransferFilter.ALL, TransferOrder.SPEED).first().hash)
    }

    @Test fun startedFlagDoesNotImplyActualTrafficAndUnknownIsNotZero() {
        val unknown = RemoteTransfer("N", "Not Observed", 100, 0, true, "/x", null, null)
        val zero = RemoteTransfer("Z", "Started but no traffic", 100, 40, true,
            "/x", 0, 0)
        val uploading = RemoteTransfer("U", "Upload only", 100, 100, true,
            "/x", 0, 20)
        val moving = RemoteTransfer("M", "Downloading", 100, 20, true,
            "/x", 120, 0)
        val stopped = RemoteTransfer("S", "Stopped", 100, 20, false, "/x", 0, 0)
        val rows = listOf(unknown, zero, uploading, moving, stopped)
        fun hashes(filter: TransferFilter) =
            TransferCatalog.visible(rows, "", filter, TransferOrder.NAME)
                .map { it.hash }.toSet()
        assertEquals(setOf("N", "Z", "U", "M"), hashes(TransferFilter.ACTIVE))
        assertEquals(setOf("U", "M"), hashes(TransferFilter.TRANSFERRING))
        assertEquals(setOf("Z"), hashes(TransferFilter.ZERO_DOWNLOAD))
        assertFalse(hashes(TransferFilter.ZERO_DOWNLOAD).contains("N"))
        assertFalse(hashes(TransferFilter.ZERO_DOWNLOAD).contains("U"))
    }

    @Test fun groupingsAreSemanticAndPhysicalSeparately() {
        assertEquals(3, TransferCatalog.places(examples).size)
        assertEquals(2, TransferCatalog.discoveredCollections(examples).size)
        assertTrue(TransferCatalog.discoveredCollections(examples).any { it.first == "Star Trek" })
    }

    @Test fun unitsAndDraftDoNotClaimServerValidation() {
        assertEquals("1.0 KiB", Units.bytes(1024))
        assertFalse(AddDraft("magnet:?xt=urn:btih:abcdef", "").canPreview)
        assertTrue(AddDraft("magnet:?xt=urn:btih:" + "a".repeat(40), "/downloads/TV").canPreview)
        assertFalse(AddDraft("https://example.org/file", "/downloads").canPreview)
        assertFalse(AddDraft("magnet:?xt=urn:btih:abcdef", "/downloads").canPreview)
        assertTrue(AddDraft("magnet:?xt=urn:btmh:1220" + "b".repeat(64), "/books").canPreview)
        assertTrue(AddDraft("magnet:?xt=urn%3Abtih%3A" + "C".repeat(32), "/downloads").canPreview)
        assertFalse(AddDraft("magnet:?xt=urn:btih:" + "a".repeat(40), "/downloads/../etc").canPreview)
    }
}
