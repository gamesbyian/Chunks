package io.github.gamesbyian.chunks.model

import org.junit.Assert.*
import org.junit.Test

class CollectionChoicePlannerTest {
    @Test fun onlyExistingNotYetAssignedNamesAreOffered() {
        val options = listOf("Star Trek", "Movies", "star trek", "Books", "bad\nname")
        assertEquals(listOf("Books", "Movies"),
            CollectionChoicePlanner.unassigned(options, listOf("STAR TREK")))
    }

    @Test fun resultIsBoundedAndNoClassifierCanImplicitlyWriteMemberships() {
        val names = (1..100).map { "Collection $it" }
        val result = CollectionChoicePlanner.unassigned(names, emptyList())
        assertEquals(CollectionChoicePlanner.MAX_QUICK_CHOICES, result.size)
        assertTrue(result.all { it in names })
        assertTrue(CollectionChoicePlanner.unassigned(emptyList(), emptyList()).isEmpty())
    }
}
