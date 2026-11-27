package io.github.gamesbyian.chunks

import org.junit.Assert.*
import org.junit.Test

class LocalFileInspectionTest {
    @Test fun suppliedChecksumHasSeparateTruthfulMatchAndMismatchStates() {
        val digest = "ab".repeat(32)
        assertEquals(LocalFileInspection.Comparison.NONE, LocalFileInspection.compare(digest, " "))
        assertEquals(LocalFileInspection.Comparison.INVALID, LocalFileInspection.compare(digest, digest + "x"))
        assertEquals(LocalFileInspection.Comparison.INVALID, LocalFileInspection.compare(digest, "x".repeat(64)))
        assertEquals(LocalFileInspection.Comparison.MATCHES, LocalFileInspection.compare(digest, digest.uppercase()))
        assertEquals(LocalFileInspection.Comparison.DIFFERS, LocalFileInspection.compare(digest, "c".repeat(64)))
    }

    @Test fun previewsAreTextOnlyAndStripTerminalAndDirectionControls() {
        val supplied = "<script>inert</script>\n\t" + '\u001b' + '\u202e' + "line"
        assertEquals("<script>inert</script>\n\t\uFFFD\uFFFDline",
            LocalFileInspection.inertText(supplied.toByteArray()))
        assertTrue(LocalFileInspection.wantsPreview("release/README.NFO"))
        assertTrue(LocalFileInspection.wantsPreview("release/checks.sfv"))
        assertFalse(LocalFileInspection.wantsPreview("release/video.mkv"))
        assertFalse(LocalFileInspection.wantsPreview("release/script.html"))
    }
}
