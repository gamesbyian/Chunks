package io.github.gamesbyian.chunks

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
}
