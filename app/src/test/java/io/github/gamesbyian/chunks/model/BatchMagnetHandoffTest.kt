package io.github.gamesbyian.chunks.model

import org.junit.Assert.*
import org.junit.Test

class BatchMagnetHandoffTest {
    private val h1 = "A".repeat(40)
    private val h2 = "b".repeat(40)
    private val v2 = "c".repeat(64)
    private val secretName = "Private tracker passkey? ABC123"
    private val available = listOf(
        BatchMagnetHandoff.Row(h1, secretName),
        BatchMagnetHandoff.Row(h2, "Other title"),
        BatchMagnetHandoff.Row(v2, "Unsupported v2")
    )

    @Test fun defaultExcludesTitlesPathsPasskeysAndTrackers() {
        val r = requireNotNull(BatchMagnetHandoff.prepare(
            available, listOf(h1, h2), includeOriginalTitles = false))
        val content = String(r.bytes)
        assertEquals(2, r.exported)
        assertEquals(0, r.unsupported)
        assertFalse(r.containsTitles)
        assertEquals(2, content.trim().lines().size)
        assertTrue(content.contains("magnet:?xt=urn:btih:" + h1.lowercase()))
        assertTrue(content.contains("magnet:?xt=urn:btih:" + h2))
        assertFalse(content.contains("passkey"))
        assertFalse(content.contains("ABC123"))
        assertFalse(content.contains("dn="))
        assertFalse(content.contains("&tr="))
    }

    @Test fun explicitlyChosenTitlesAreEscapedAndNeverInsertTrackersOrExtraLines() {
        val row = BatchMagnetHandoff.Row(h1, "a title &\ntr=https://private.invalid/?passkey=bad")
        val r = requireNotNull(BatchMagnetHandoff.prepare(
            listOf(row), listOf(h1), true))
        val text = String(r.bytes)
        assertTrue(r.containsTitles)
        assertEquals(1, text.trim().lines().size)
        assertTrue(text.contains("&dn="))
        assertFalse(text.contains("&tr="))
        assertFalse(text.contains("\ntr="))
        assertTrue(text.contains("%3D") || text.contains("%3F"))
    }

    @Test fun countsUnsupportedV2OnlyAndNeverClaimsRestorableTransfer() {
        val r = requireNotNull(BatchMagnetHandoff.prepare(available,
            listOf(v2, h1), false))
        assertEquals(2, r.selected)
        assertEquals(1, r.exported)
        assertEquals(1, r.unsupported)
        assertFalse(String(r.bytes).contains(v2))
        assertNull(BatchMagnetHandoff.prepare(available, listOf(v2), false))
    }

    @Test fun absentDuplicateAndOversizedSelectionFailClosed() {
        assertNull(BatchMagnetHandoff.prepare(available,
            listOf("z".repeat(40)), false))
        assertNull(BatchMagnetHandoff.prepare(available,
            listOf(h1, h1.lowercase()), false))
        assertNull(BatchMagnetHandoff.prepare(available + available[0],
            listOf(h1), false))
        assertNull(BatchMagnetHandoff.prepare(available,
            List(513) { h1 }, false))
        assertNull(BatchMagnetHandoff.prepare(available, emptyList(), false))
    }

    @Test fun capPreservesAllAcceptedItemsAndDoesNotTruncateSilently() {
        val rows = (0 until 512).map { index ->
            BatchMagnetHandoff.Row(index.toString(16).padStart(40, '0'), "Title $index")
        }
        val prepared = requireNotNull(BatchMagnetHandoff.prepare(
            rows, rows.map { it.infoHash }, false))
        assertEquals(512, prepared.exported)
        assertEquals(512, String(prepared.bytes).trim().lines().size)
        assertTrue(prepared.bytes.size < BatchMagnetHandoff.MAX_BYTES)
    }
}
