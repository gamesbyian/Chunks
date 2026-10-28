package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalTrackerSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTrackerSummaryTextTest {
    private fun text(total: Int, working: Int = 0, updating: Int = 0, failing: Int = 0,
                     stopped: Boolean = false, dht: Boolean = false) =
        LocalTrackerSummaryText.describe(LocalTrackerSummary(total, working, updating, failing), stopped, dht)

    @Test fun describesEachTrackerStateWithoutUrls() {
        assertNull(LocalTrackerSummaryText.describe(null, false, false))
        assertTrue(text(0)!!.contains("DHT is off"))
        assertTrue(text(0, dht = true)!!.contains("only come from DHT"))
        assertEquals("Trackers: 3 listed (contacted after Start).", text(3, stopped = true))
        assertEquals("Trackers: 1 of 2 responding.", text(2, working = 1, failing = 1))
        assertEquals("Trackers: contacting 2…", text(2, updating = 2))
        assertEquals("Trackers: none of 2 responding yet.", text(2, failing = 2))
        assertEquals("Trackers: 2 listed, not contacted yet.", text(2))
    }
}
