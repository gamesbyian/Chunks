package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Test

/** A narrowed rTorrent list states how much it hides, like the phone's "Showing X of Y". */
class RemoteShownCountTest {
    @Test fun narrowedListsNameTheLoadedTotal() {
        assertEquals("12 shown", remoteShownCount(12, 12))
        assertEquals("3 of 12 shown", remoteShownCount(3, 12))
        assertEquals("0 of 12 shown", remoteShownCount(0, 12))
    }

    @Test fun combinedCountNamesItsSelectedTotalWhenNarrowed() {
        assertEquals("Showing 4", combinedShowingText(4, 4))
        assertEquals("Showing 1 of 4", combinedShowingText(1, 4))
    }
}
