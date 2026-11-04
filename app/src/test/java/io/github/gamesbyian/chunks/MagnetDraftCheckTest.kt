package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagnetDraftCheckTest {
    private val hex = "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"
    // Python: base64.b32encode(bytes.fromhex(hex)); independent of this code.
    private val base32 = "3WBFL3G4PSSV7MF37AJSHWDQMLNR63I4"

    private fun valid(text: String) = MagnetDraftCheck.inspect(text) as MagnetDraftCheck.Valid
    private fun problem(text: String) = (MagnetDraftCheck.inspect(text) as MagnetDraftCheck.Problem).message

    @Test fun emptyDraftIsNeitherValidNorAProblem() {
        assertEquals(MagnetDraftCheck.Empty, MagnetDraftCheck.inspect("   "))
    }

    @Test fun hexMagnetShowsNameHashAndTrackersAndKeepsTheRestOfTheLink() {
        val link = "magnet:?xt=urn:btih:$hex&dn=Big+Buck%20Bunny&tr=udp%3A%2F%2Ftracker.example%3A1337&tr=https%3A%2F%2Fb.example%2Fannounce"
        val check = valid(link)
        assertEquals(hex, check.infoHash)
        assertEquals("Big Buck Bunny", check.displayName)
        assertEquals(2, check.trackerCount)
        assertEquals(link, check.normalized)
        assertFalse(check.convertedFromBase32)
    }

    @Test fun base32HashIsTheSameTorrentInHex() {
        assertEquals(hex, MagnetDraftCheck.base32ToHex(base32))
        assertEquals(hex, MagnetDraftCheck.base32ToHex(base32.lowercase()))
        val check = valid("magnet:?dn=x&xt=urn:btih:$base32&tr=udp%3A%2F%2Ft.example%3A1")
        assertEquals(hex, check.infoHash)
        assertTrue(check.convertedFromBase32)
        // Only the identity changes; order and trackers are kept byte for byte.
        assertEquals("magnet:?dn=x&xt=urn:btih:$hex&tr=udp%3A%2F%2Ft.example%3A1", check.normalized)
    }

    @Test fun normalizedLinkPassesTheExistingStrictServerValidator() {
        val plan = io.github.gamesbyian.chunks.rpc.StoppedRemoteAddPlan.fromMagnet(
            valid("magnet:?xt=urn:btih:$base32").normalized, "/downloads")
        assertEquals(hex, plan.expectedInfoHash)
    }

    @Test fun uppercaseAndPercentEncodedHexBecomePlainLowercase() {
        assertEquals("magnet:?xt=urn:btih:$hex", valid("magnet:?xt=urn:btih:${hex.uppercase()}").normalized)
        assertEquals("magnet:?xt=urn:btih:$hex", valid("magnet:?xt=urn%3Abtih%3A$hex").normalized)
    }

    @Test fun noNameMeansTheNameArrivesWithMetadata() {
        assertNull(valid("magnet:?xt=urn:btih:$hex").displayName)
        assertNull(valid("magnet:?xt=urn:btih:$hex&dn=%20%20").displayName)
    }

    @Test fun displayNameIsBoundedAndControlFree() {
        val name = valid("magnet:?xt=urn:btih:$hex&dn=a%E2%80%AEb%0Dc").displayName
        assertEquals("abc", name)
        val long = valid("magnet:?xt=urn:btih:$hex&dn=" + "n".repeat(400)).displayName!!
        assertTrue(long.length <= 160 && long.endsWith("…"))
    }

    @Test fun problemsAreSpecific() {
        assertEquals(MagnetDraftCheck.NOT_A_MAGNET, problem("https://example.com/file.torrent"))
        assertEquals(MagnetDraftCheck.NO_HASH, problem("magnet:?dn=only-a-name"))
        assertEquals(MagnetDraftCheck.V2_ONLY, problem("magnet:?xt=urn:btmh:1220" + "a".repeat(64)))
        assertEquals(MagnetDraftCheck.SEVERAL_HASHES,
            problem("magnet:?xt=urn:btih:$hex&xt=urn:btih:${"0".repeat(40)}"))
        assertEquals(MagnetDraftCheck.BAD_HASH, problem("magnet:?xt=urn:btih:${hex.dropLast(1)}"))
        assertEquals(MagnetDraftCheck.BAD_HASH, problem("magnet:?xt=urn:btih:${"g".repeat(40)}"))
        assertEquals(MagnetDraftCheck.CONTROL, problem("magnet:?xt=urn:btih:$hex\n&dn=x"))
        assertEquals(MagnetDraftCheck.TOO_LONG, problem("magnet:?xt=urn:btih:$hex&dn=" + "x".repeat(8200)))
    }

    @Test fun hybridMagnetWithV1HashIsAccepted() {
        val check = valid("magnet:?xt=urn:btih:$hex&xt=urn:btmh:1220" + "b".repeat(64))
        assertEquals(hex, check.infoHash)
    }

    @Test fun malformedPercentEscapeNeverThrows() {
        assertEquals(hex, valid("magnet:?xt=urn:btih:$hex&dn=%ZZ&tr=%E0").infoHash)
    }

    @Test fun shortHashKeepsBothEnds() {
        assertEquals("dd8255ec…db1f6d1c", MagnetDraftCheck.shortHash(hex))
    }
}
