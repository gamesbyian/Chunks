package io.github.gamesbyian.chunks.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaNetworkChangeTrackerTest {
    @Test fun initialSnapshotAndRepeatedTickerAreNotExtraEnrichmentJobs() {
        val detector = MediaNetworkChangeTracker()
        val offline = MediaNetworkState(online = false, metered = false)
        assertFalse(detector.changedAfterFirstObservation(offline))
        repeat(30) { assertFalse(detector.changedAfterFirstObservation(offline)) }
    }

    @Test fun restoredInternetNewMeteringAndPowerSaverRescheduleExactlyOnChange() {
        val detector = MediaNetworkChangeTracker()
        assertFalse(detector.changedAfterFirstObservation(MediaNetworkState(false, true, true)))
        assertTrue("Return to Wi-Fi must retry eligible offscreen media",
            detector.changedAfterFirstObservation(MediaNetworkState(true, false, true)))
        assertFalse(detector.changedAfterFirstObservation(MediaNetworkState(true, false, true)))
        assertTrue("Power saver exit may unblock artwork",
            detector.changedAfterFirstObservation(MediaNetworkState(true, false, false)))
        assertFalse(detector.changedAfterFirstObservation(MediaNetworkState(true, false, false)))
        assertTrue("Metered network must force a fresh policy check",
            detector.changedAfterFirstObservation(MediaNetworkState(true, true, false)))
        assertFalse(detector.changedAfterFirstObservation(MediaNetworkState(true, true, false)))
        assertTrue("Network loss revokes active outbound entitlement",
            detector.changedAfterFirstObservation(MediaNetworkState(false, true, false)))
    }
}
