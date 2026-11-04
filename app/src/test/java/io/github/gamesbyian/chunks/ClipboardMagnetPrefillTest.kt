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
        assertNull(ClipboardMagnetPrefill.decide(true, "", false, "https://example.invalid/file.torrent"))
        assertNull(ClipboardMagnetPrefill.decide(true, "", false, "magnet:?xt=urn:btih:abc"))
    }
}
