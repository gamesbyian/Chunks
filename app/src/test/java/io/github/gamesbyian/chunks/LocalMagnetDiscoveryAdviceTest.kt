package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMagnetDiscoveryAdviceTest {
    private val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40)

    @Test fun trackerlessMagnetWarnsOnlyWhenDhtIsOff() {
        assertTrue(LocalMagnetDiscoveryAdvice.warn(magnet, dhtPreferred = false))
        assertFalse(LocalMagnetDiscoveryAdvice.warn(magnet, dhtPreferred = true))
    }

    @Test fun usableTrackerAvoidsMissingDiscoveryRouteWarning() {
        val valid = magnet + "&tr=udp%3A%2F%2Ftracker.example%3A6969"
        assertFalse(LocalMagnetDiscoveryAdvice.warn(valid, dhtPreferred = false))
    }

    @Test fun malformedTrackerDoesNotSuppressTheWarning() {
        assertTrue(LocalMagnetDiscoveryAdvice.warn(magnet + "&tr=%GG", dhtPreferred = false))
        assertTrue(LocalMagnetDiscoveryAdvice.warn(magnet + "&tr=not-a-tracker", dhtPreferred = false))
    }

    @Test fun missingOrUnboundedMagnetIsNotPresentedAsVerifiedDiscoveryAdvice() {
        assertFalse(LocalMagnetDiscoveryAdvice.warn("", dhtPreferred = false))
        assertFalse(LocalMagnetDiscoveryAdvice.warn("anything else", dhtPreferred = false))
        assertFalse(LocalMagnetDiscoveryAdvice.warn(magnet + "x".repeat(8192), dhtPreferred = false))
    }

    @Test fun aWellFormedDirectPeerReplacesTheMissingRouteWarning() {
        val copy = magnet + "&x.pe=203.0.113.9:51413&x.pe=%5B2001%3Adb8%3A%3A7%5D%3A6881"
        assertEquals(listOf("203.0.113.9:51413", "[2001:db8::7]:6881"),
            LocalMagnetDiscoveryAdvice.directPeers(copy))
        assertFalse(LocalMagnetDiscoveryAdvice.warn(copy, dhtPreferred = false))
    }

    @Test fun malformedOrHostnamePeersAreIgnored() {
        for (bad in listOf("seed.example:51413", "999.1.1.1:80", "203.0.113.9:0",
                "203.0.113.9:70000", "203.0.113.9", "[not-ipv6]:80", "%ZZ")) {
            assertEquals(bad, emptyList<String>(), LocalMagnetDiscoveryAdvice.directPeers("$magnet&x.pe=$bad"))
            assertTrue(bad, LocalMagnetDiscoveryAdvice.warn("$magnet&x.pe=$bad", dhtPreferred = false))
        }
    }
}
