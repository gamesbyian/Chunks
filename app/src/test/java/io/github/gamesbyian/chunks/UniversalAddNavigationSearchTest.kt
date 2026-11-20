package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Test

/** A generated exact-transfer lookup must not masquerade as a permanent user search. */
class UniversalAddNavigationSearchTest {
    private val firstHash = "ab".repeat(20)
    @Test fun onlyTheSameInjectedHashIsClearedOnEnteringANewAdd() {
        assertEquals("", searchAfterNewAdd(firstHash, firstHash))
        assertEquals("my show", searchAfterNewAdd("my show", firstHash))
        assertEquals(firstHash, searchAfterNewAdd(firstHash, null))
        assertEquals("other", searchAfterNewAdd("other", "cd".repeat(20)))
    }
}
