package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Time left is an estimate from the reported rate, never a promise. */
class TimeLeftTest {
    private val mib = 1024L * 1024

    @Test fun formatsMinutesHoursAndDays() {
        assertEquals("less than 1 min left", Units.timeLeft(30 * mib, mib))
        assertEquals("about 2 min left", Units.timeLeft(90 * mib, mib))
        assertEquals("about 1 h left", Units.timeLeft(3600 * mib, mib))
        assertEquals("about 2 h 30 min left", Units.timeLeft(9000 * mib, mib))
        assertEquals("about 2 d 3 h left", Units.timeLeft((51L * 3600) * mib, mib))
    }

    @Test fun noEstimateWithoutMeaningfulRateOrRemainingWork() {
        assertNull(Units.timeLeft(0, mib))
        assertNull(Units.timeLeft(-5, mib))
        assertNull(Units.timeLeft(mib, null))
        assertNull("under 1 KiB/s is noise, not a trend", Units.timeLeft(mib, 1023))
        assertNull("beyond a week is not a useful estimate", Units.timeLeft(8L * 24 * 3600 * 2048, 2048))
    }
}
