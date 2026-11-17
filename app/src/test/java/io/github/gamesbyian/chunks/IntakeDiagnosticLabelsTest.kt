package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Shared Android intent input is untrusted even in a diagnostic log line. */
class IntakeDiagnosticLabelsTest {
    @Test fun knownSourceMimeTypesUseFixedLabels() {
        assertEquals("none", IntakeDiagnosticLabels.mime(null))
        assertEquals("torrent", IntakeDiagnosticLabels.mime(" application/x-bittorrent "))
        assertEquals("torrent", IntakeDiagnosticLabels.mime("APPLICATION/X-TORRENT"))
        assertEquals("torrent", IntakeDiagnosticLabels.mime("application/vnd.bittorrent"))
        assertEquals("generic-binary", IntakeDiagnosticLabels.mime("application/octet-stream"))
        assertEquals("text", IntakeDiagnosticLabels.mime("text/plain"))
        assertEquals("uri-list", IntakeDiagnosticLabels.mime("text/uri-list"))
    }

    @Test fun unknownMimeStringsCannotLeakTokensToLogcat() {
        val marker = "private-passkey-49501"
        for (mime in listOf(
            "application/x-torrent-$marker",
            "application/octet-stream;token=$marker",
            marker,
            "image/png"
        )) {
            assertEquals("other", IntakeDiagnosticLabels.mime(mime))
            assertFalse(IntakeDiagnosticLabels.mime(mime).contains(marker))
        }
    }

    @Test fun schemeMustAlsoBeAnAllowlistedFixedToken() {
        assertEquals("none", IntakeDiagnosticLabels.scheme(null))
        for (scheme in listOf("content", "file", "magnet", "http", "https")) {
            assertEquals(scheme, IntakeDiagnosticLabels.scheme(scheme.uppercase()))
        }
        assertEquals("other", IntakeDiagnosticLabels.scheme("secret-passkey"))
        assertEquals("other", IntakeDiagnosticLabels.scheme("custom+tracker"))
    }
}
