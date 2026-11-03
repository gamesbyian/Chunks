package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A name suffix is a clue, never a verified file or artwork identity. */
class TransferFileTypeBadgeTest {
    @Test fun commonKnownExtensionHasCompactNameOnlyHint() {
        val hint = transferNameFormatHint("Series.S01E01.MKV")
        assertEquals("MKV", hint.badge)
        assertTrue(hint.explanation.contains("not verified"))
    }

    @Test fun torrentRootAndUnsupportedOrSensitiveNamesStayGeneric() {
        listOf("", "Season.1", "Mixed release", "archive.unknown", "Movie.S01E01",
            "some-other-file", "../folder/", "secret.encrypted").forEach { name ->
            assertEquals("BT", transferNameFormatHint(name).badge)
        }
    }

    @Test fun noUserPathsOrQueryStringsAreRenderedAsBadge() {
        assertEquals("PDF", transferNameFormatHint("private/notes.PDF").badge)
        assertEquals("BT", transferNameFormatHint("video.mkv?token=sensitive").badge)
        assertTrue(transferNameFormatHint("dir/user/notes.pdf").explanation
            .startsWith("Name suggests .pdf"))
    }

    @Test fun multiFileNameWithoutARecognizedExtensionIsNeverGuessed() {
        assertEquals("BT", transferNameFormatHint("Collection.S01E01.2026").badge)
        assertEquals("BT", transferNameFormatHint("film.mkv and film.srt").badge)
    }
}
