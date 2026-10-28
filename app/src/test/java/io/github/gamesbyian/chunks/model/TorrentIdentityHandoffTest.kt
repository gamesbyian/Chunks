package io.github.gamesbyian.chunks.model

import org.junit.Assert.*
import org.junit.Test

class TorrentIdentityHandoffTest {
    private val hash = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"

    @Test fun generatesCanonicalV1MagnetWithoutPathsOrTrackers() {
        val name = "A series S01E03 & some things"
        val magnet = TorrentIdentityHandoff.prepare(hash, name)
        assertEquals("magnet:?xt=urn:btih:" + hash.lowercase() +
            "&dn=A%20series%20S01E03%20%26%20some%20things", magnet)
        assertFalse(magnet!!.contains("tr="))
        assertFalse(magnet.contains("/downloads/"))
    }

    @Test fun supportsEmptyAndUnicodeNamesWithBoundedOutput() {
        assertEquals("magnet:?xt=urn:btih:" + hash.lowercase(),
            TorrentIdentityHandoff.prepare(hash, ""))
        val longName = "☃".repeat(1000) + "\u202E"
        val magnet = requireNotNull(TorrentIdentityHandoff.prepare(hash, longName))
        assertTrue(magnet.length < 1500)
        assertFalse(magnet.contains("\u202E"))
        assertTrue(magnet.contains("%E2%98%83"))
    }

    @Test fun rejectsV2HybridOrInvalidHashesWithoutBuildingFallbacks() {
        assertNull(TorrentIdentityHandoff.prepare("g".repeat(40), "name"))
        assertNull(TorrentIdentityHandoff.prepare("f".repeat(64), "name"))
        assertNull(TorrentIdentityHandoff.prepare(" f".repeat(20), "name"))
    }

    @Test fun stripsSpoofingAndControlCharacters() {
        val magnet = requireNotNull(TorrentIdentityHandoff.prepare(hash, "One\r\n\u202ETwo"))
        assertTrue(magnet.contains("OneTwo"))
        assertFalse(magnet.contains("%0D"))
        assertFalse(magnet.contains("%0A"))
    }
}
