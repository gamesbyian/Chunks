package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSpeedLimitTest {
    @Test fun blankAndZeroMeanUnlimited() {
        assertEquals(0, parseSpeedLimit(""))
        assertEquals(0, parseSpeedLimit("  "))
        assertEquals(0, parseSpeedLimit("0"))
    }

    @Test fun kibibytesBecomeBytesPerSecond() {
        assertEquals(512 * 1024, parseSpeedLimit("512"))
        assertEquals(1024, parseSpeedLimit(" 1 "))
    }

    @Test fun refusesNegativeFractionalTextAndOverflow() {
        for (bad in listOf("-1", "1.5", "fast", "2097152", "99999999999")) assertNull(bad, parseSpeedLimit(bad))
    }
}
