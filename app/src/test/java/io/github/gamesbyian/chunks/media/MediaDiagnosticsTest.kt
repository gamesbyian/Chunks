package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.model.MediaMetadataCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDiagnosticsTest {
    private val attr = MediaAttribution("TVmaze", "CC BY-SA 4.0", null)
    private fun c(id: String, title: String) = MediaCandidate(MediaMetadataCacheKey("tvmaze", "show", id), MediaKind.TV_SERIES, title, attribution = attr)

    @Test fun countsAreTitleFree() {
        val list = listOf(
            TransferMediaAssignment(MediaTransferKey("a|1"), MediaAssignmentState.AUTOMATIC, c("475", "Secret Show Name")),
            TransferMediaAssignment(MediaTransferKey("a|2"), MediaAssignmentState.CHOSEN_BY_USER, c("1292", "The Office"), suggestions = listOf(c("526", "The Office"))),
            TransferMediaAssignment(MediaTransferKey("a|3"), MediaAssignmentState.SUGGESTED, suggestions = listOf(c("1", "X"))),
            TransferMediaAssignment(MediaTransferKey("a|4"), MediaAssignmentState.COULDNT_IDENTIFY))
        val d = MediaDiagnostics.of(list)
        assertEquals(2, d.byProvider["tvmaze"]); assertEquals(1, d.corrections)
        val text = d.summary { if (it == "tvmaze") "TVmaze" else it }
        assertTrue(text, text.contains("Automatic 1") && text.contains("TVmaze 2"))
        assertFalse(text.contains("Secret") || text.contains("Office") || text.contains("475"))
        assertEquals("No transfers identified yet", MediaDiagnostics.of(emptyList()).summary())
        assertTrue(text, text.lines().last() == "Corrections: 1") // never appended to the provider line
        assertEquals("No transfers identified yet", MediaDiagnostics.of(listOf(TransferMediaAssignment(MediaTransferKey("a|9"),
            MediaAssignmentState.NOT_LOOKED_UP))).summary())
    }

    @Test fun localCoverFinderPrefersConventionalShallowCovers() {
        fun f(p: String, s: Long? = 50_000) = MediaFileHint(p, s)
        assertEquals("Album/cover.jpg", LocalCoverFinder.pick(listOf(f("Album/01.flac", 30_000_000), f("Album/Scans/front.jpg"),
            f("Album/cover.jpg"), f("Album/folder.jpg")))!!.path)
        assertEquals("Show/poster.png", LocalCoverFinder.pick(listOf(f("Show/poster.png"), f("Show/S01E01.mkv", 1_000_000_000)))!!.path)
        assertNull(LocalCoverFinder.pick(listOf(f("Album/screenshot.jpg"), f("Album/cover.gif"), f("a/b/c/d/cover.jpg"), f("cover.jpg", 100))))
    }
}
