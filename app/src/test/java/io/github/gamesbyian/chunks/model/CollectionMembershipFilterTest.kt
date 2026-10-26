package io.github.gamesbyian.chunks.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionMembershipFilterTest {
    @Test fun noSelectedCollectionShowsAllTransfersEvenWithoutMemberships() {
        assertTrue(CollectionMembershipFilter.matches(null, emptyList()))
        assertTrue(CollectionMembershipFilter.matches(null, listOf("Star Trek")))
    }

    @Test fun selectedCollectionMatchesOnlyExactMembershipNamesIgnoringCase() {
        assertTrue(CollectionMembershipFilter.matches("star trek", listOf("Star Trek", "Science Fiction")))
        assertFalse(CollectionMembershipFilter.matches("Star Trek", emptyList()))
        assertFalse(CollectionMembershipFilter.matches("Star Trek", listOf("Star Trekkin", "Star")))
        assertFalse(CollectionMembershipFilter.matches("Star Trek", listOf("Doctor Who")))
    }
}
