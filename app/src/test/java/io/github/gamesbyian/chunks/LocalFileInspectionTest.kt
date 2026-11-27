package io.github.gamesbyian.chunks

import org.junit.Assert.*
import org.junit.Test

class LocalFileInspectionTest {
    private fun compare(digest: String, supplied: String) =
        LocalChecksumComparison.compare(LocalChecksumComparison.Algorithm.SHA256, digest, supplied, "Release/file.bin")

    @Test fun onlyExactNamedSingleChecksumRowsAreCompared() {
        val digest = "ab".repeat(32)
        assertEquals(LocalChecksumComparison.State.MATCHES, compare(digest, digest + "  Release/file.bin"))
        assertEquals(LocalChecksumComparison.State.MATCHES, compare(digest, digest.uppercase() + " *Release/file.bin"))
        assertEquals(LocalChecksumComparison.State.WRONG_FILE, compare(digest, digest + "  file.bin"))
        assertEquals(LocalChecksumComparison.State.WRONG_FILE, compare(digest, digest + "  release/file.bin"))
        assertEquals(LocalChecksumComparison.State.WRONG_FILE, compare(digest, digest + "  Release/../file.bin"))
        assertEquals(LocalChecksumComparison.State.INVALID, compare(digest, digest + "  Release/file.bin\n" + digest))
        assertEquals(LocalChecksumComparison.State.INVALID, compare(digest, "x".repeat(4097)))
        assertEquals(LocalChecksumComparison.State.INVALID, compare(digest, digest + "  Release/fi\u202ele.bin"))
        assertEquals(LocalChecksumComparison.State.DIFFERS, compare(digest, "cd".repeat(32) + "  Release/file.bin"))
    }

    @Test fun sfvAndLegacyDigestLengthsAreDistinct() {
        val crc = LocalChecksumComparison.Algorithm.CRC32
        val sha1 = LocalChecksumComparison.Algorithm.SHA1
        val md5 = LocalChecksumComparison.Algorithm.MD5
        fun check(algorithm: LocalChecksumComparison.Algorithm, digest: String, input: String) =
            LocalChecksumComparison.compare(algorithm, digest, input, "Release/file name.bin")
        assertEquals(LocalChecksumComparison.State.MATCHES, check(crc, "cbf43926", "Release/file name.bin CBF43926"))
        assertEquals(LocalChecksumComparison.State.WRONG_FILE, check(crc, "cbf43926", "file name.bin CBF43926"))
        assertEquals(LocalChecksumComparison.State.INVALID, check(crc, "cbf43926", "cbf43926  Release/file name.bin"))
        assertEquals(LocalChecksumComparison.State.MATCHES, check(sha1, "ab".repeat(20), "AB".repeat(20)))
        assertEquals(LocalChecksumComparison.State.INVALID, check(sha1, "ab".repeat(20), "ab".repeat(16)))
        assertEquals(LocalChecksumComparison.State.MATCHES, check(md5, "ab".repeat(16), "ab".repeat(16) + " *Release/file name.bin"))
    }

    @Test fun suppliedChecksumHasSeparateTruthfulMatchAndMismatchStates() {
        val digest = "ab".repeat(32)
        assertEquals(LocalChecksumComparison.State.NONE, compare(digest, " "))
        assertEquals(LocalChecksumComparison.State.INVALID, compare(digest, digest + "x"))
        assertEquals(LocalChecksumComparison.State.INVALID, compare(digest, "x".repeat(64)))
        assertEquals(LocalChecksumComparison.State.MATCHES, compare(digest, digest.uppercase()))
        assertEquals(LocalChecksumComparison.State.DIFFERS, compare(digest, "c".repeat(64)))
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
