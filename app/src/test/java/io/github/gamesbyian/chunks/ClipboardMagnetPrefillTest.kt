package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Source precedence: typed/restored draft > inbound item > clipboard > empty. */
class ClipboardMagnetPrefillTest {
    private val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40) + "&dn=x"

    @Test fun anEmptyDraftTakesAValidCopiedMagnet() {
        assertEquals(magnet, ClipboardMagnetPrefill.decide(true, "", false, "  $magnet\n"))
    }

    @Test fun base32IsFilledInItsStandardForm() {
        assertEquals("magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c",
            ClipboardMagnetPrefill.decide(true, "", false, "magnet:?xt=urn:btih:3WBFL3G4PSSV7MF37AJSHWDQMLNR63I4"))
    }

    @Test fun nothingElseIsEverReplacedOrFilled() {
        assertNull(ClipboardMagnetPrefill.decide(false, "", false, magnet))
        assertNull(ClipboardMagnetPrefill.decide(true, "magnet:?typed", false, magnet))
        assertNull(ClipboardMagnetPrefill.decide(true, "", true, magnet))
        assertNull(ClipboardMagnetPrefill.decide(true, "", false, null))
        assertNull(ClipboardMagnetPrefill.decide(true, "", false, "a shopping list"))
        assertNull(ClipboardMagnetPrefill.decide(true, "", false, "https://news.example/today"))
        assertNull(ClipboardMagnetPrefill.decide(true, "", false, "http://example.invalid/file.torrent"))
        assertNull(ClipboardMagnetPrefill.decide(true, "", false, "magnet:?xt=urn:btih:abc"))
    }

    @Test fun twoClipboardRepresentationsMustMatchTheExactSourceBeforePrefill() {
        val text = magnet + "&tr=https%3A%2F%2Ftracker.example%2FprivateA"
        val changed = magnet + "&tr=https%3A%2F%2Ftracker.example%2FprivateB"
        val upper = "MAGNET:?" + text.substring("magnet:?".length)
        assertEquals(text, ClipboardMagnetPrefill.exactClipboardSource(text, upper))
        assertEquals(text, ClipboardMagnetPrefill.decide(true, "", false,
            ClipboardMagnetPrefill.exactClipboardSource(text, upper)))

        // A matching infohash does not authorize replacing a tracker token.
        assertNull(ClipboardMagnetPrefill.exactClipboardSource(text, changed))
        assertNull(ClipboardMagnetPrefill.exactClipboardSource(text,
            "content://provider.example/download/other.torrent"))
        assertNull(ClipboardMagnetPrefill.exactClipboardSource("ordinary text", text))
        assertNull(ClipboardMagnetPrefill.exactClipboardSource(text,
            "https://example.org/download/other.torrent"))

        val url = "https://example.org/download/item.torrent?token=privateA"
        assertEquals(url, ClipboardMagnetPrefill.exactClipboardSource(url, url))
        assertNull(ClipboardMagnetPrefill.exactClipboardSource(url,
            "https://example.org/download/item.torrent?token=privateB"))
        assertEquals(text, ClipboardMagnetPrefill.exactClipboardSource(text, null))
        assertEquals(text, ClipboardMagnetPrefill.exactClipboardSource(null, text))
        assertNull(ClipboardMagnetPrefill.exactClipboardSource(null,
            "content://provider.example/document/item.torrent"))
    }

    @Test fun aTorrentShapedHttpsLinkIsFilledAsTextOnly() {
        val link = "https://linuxtracker.org/torrents/abc/download/"
        assertEquals(link, ClipboardMagnetPrefill.decide(true, "", false, " $link\n"))
        assertNull(ClipboardMagnetPrefill.decide(true, "typed", false, link))
    }
}
