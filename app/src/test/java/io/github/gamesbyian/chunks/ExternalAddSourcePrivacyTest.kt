package io.github.gamesbyian.chunks

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Private source values must never escape via data-class default diagnostics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExternalAddSourcePrivacyTest {
    @Test fun magnetTorrentLinkAndDocumentUriRenderOnlyNonSensitiveTypes() {
        val marker = "secret-passkey-123"
        val magnet = ExternalAddSource.Magnet(
            "magnet:?xt=urn:btih:" + "a".repeat(40) +
                "&tr=https%3A%2F%2Ftracker.example%2F" + marker)
        val link = ExternalAddSource.TorrentLink(
            "https://tracker.example/private/$marker/download.torrent")
        val doc = ExternalAddSource.TorrentDocument(
            Uri.parse("content://documents.example/user/$marker/private.torrent"))

        assertEquals("ExternalAddSource.Magnet(private)", magnet.toString())
        assertEquals("ExternalAddSource.TorrentLink(private)", link.toString())
        assertEquals("ExternalAddSource.TorrentDocument(private)", doc.toString())
        for (source in listOf(magnet, link, doc)) {
            assertFalse(source.toString().contains(marker))
            assertFalse(source.toString().contains("tracker.example"))
            assertFalse(source.toString().contains("content://"))
        }
        // Redacting the diagnostic representation cannot change the full,
        // exact source equality, hash or copy needed for safe Add precedence.
        assertEquals(magnet, magnet.copy())
        assertEquals(link, link.copy())
        assertEquals(doc, doc.copy())
        assertEquals(magnet.hashCode(), magnet.copy().hashCode())
    }
}
