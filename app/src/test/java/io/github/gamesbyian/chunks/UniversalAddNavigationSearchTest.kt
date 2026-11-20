package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Test

/** A generated exact-transfer lookup must not masquerade as a permanent user search. */
class UniversalAddNavigationSearchTest {
    private val firstHash = "ab".repeat(20)
    @Test fun endingAnAddLookupRestoresTheManualSearch() {
        val search = UniversalAddNavigationSearch()
        assertEquals("my show", search.displayQuery("my show"))
        search.viewTransfer(firstHash)
        assertEquals(firstHash, search.displayQuery("my show"))
        search.clear()
        assertEquals("my show", search.displayQuery("my show"))
        assertEquals(firstHash, search.displayQuery(firstHash))
    }
}
