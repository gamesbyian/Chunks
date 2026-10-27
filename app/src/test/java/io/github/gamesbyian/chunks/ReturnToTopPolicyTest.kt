package io.github.gamesbyian.chunks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnToTopPolicyTest {
    @Test fun remoteOnlyAfterMeaningfulScrollWithActualVisibleTransfers() {
        assertFalse(ReturnToTopPolicy.remote(0, 0, 800, true))
        assertFalse(ReturnToTopPolicy.remote(1, 100, 800, true))
        assertTrue(ReturnToTopPolicy.remote(2, 0, 800, true))
        assertTrue(ReturnToTopPolicy.remote(0, 800, 800, true))
        assertFalse(ReturnToTopPolicy.remote(5, 0, 800, false))
        assertFalse(ReturnToTopPolicy.remote(5, 0, 0, true))
    }

    @Test fun localRejectsEmptyShortAndNearTopLists() {
        assertFalse(ReturnToTopPolicy.local(1200, 5000, 1000, false))
        assertFalse(ReturnToTopPolicy.local(900, 950, 1000, true))
        assertFalse(ReturnToTopPolicy.local(300, 5000, 1000, true))
        assertTrue(ReturnToTopPolicy.local(750, 5000, 1000, true))
        assertTrue(ReturnToTopPolicy.local(2300, 5000, 1000, true))
        assertFalse(ReturnToTopPolicy.local(1000, 5000, 0, true))
    }
}
