package io.github.gamesbyian.chunks.rpc

import org.junit.Assert.*
import org.junit.Test

class RemoteTrackerHealthTest {
    @Test fun summariesDoNotInventPeerConnectivity() {
        assertTrue(RemoteTrackerHealth(0, 0, 0, 0, 0).summary().contains("No trackers"))
        assertTrue(RemoteTrackerHealth(2, 0, 0, 0, null).summary().contains("disabled"))
        assertTrue(RemoteTrackerHealth(3, 2, 0, 8, 0).summary().contains("failed"))
        assertTrue(RemoteTrackerHealth(2, 2, 0, 0, null).summary().contains("No successful"))
        assertTrue(RemoteTrackerHealth(2, 2, 4, 0, 0).summary().contains("no peers"))
        val announced = RemoteTrackerHealth(2, 2, 5, 1, 12).summary()
        assertTrue(announced.contains("previously reported"))
        assertTrue(announced.contains("does not prove"))
        assertTrue(RemoteTrackerHealth(2, 2, 5, 1, null).summary().contains("unsupported"))
    }

    @Test fun rejectInvalidOrImpossibleHistory() {
        for (bad in listOf(
            listOf(-1, 0, 0, 0), listOf(257, 0, 0, 0),
            listOf(1, 2, 0, 0), listOf(1, 1, -1, 0))) {
            try {
                RemoteTrackerHealth(bad[0], bad[1], bad[2].toLong(), bad[3].toLong(), 0)
                fail("Should reject invalid tracker observations")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
