package io.github.gamesbyian.chunks.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionMembershipRulesTest {
    @Test fun membershipsAreManyToManyAndCaseInsensitiveWithoutRenamingFiles() {
        val one = CollectionMembershipRules.add(emptySet(), "  Star Trek  ")!!
        val two = CollectionMembershipRules.add(one, "Science Fiction")!!
        assertEquals(setOf("Star Trek", "Science Fiction"), two)
        assertEquals(two, CollectionMembershipRules.add(two, "STAR TREK"))
        assertEquals(setOf("Science Fiction"), CollectionMembershipRules.remove(two, "star trek"))
        assertTrue(CollectionMembershipRules.remove(two, "missing").size == 2)
    }

    @Test fun rejectsControlSpoofingBlankAndOversizedNames() {
        for (invalid in listOf("", "   ", "bad\nname", "bidi\u202Ereversal",
            "a".repeat(81), "🔥".repeat(65))) {
            assertNull(invalid, CollectionMembershipRules.cleanName(invalid))
            assertNull(CollectionMembershipRules.add(emptySet(), invalid))
        }
        assertEquals("Star Trek: TNG", CollectionMembershipRules.cleanName(" Star Trek: TNG "))
        assertEquals("🎬 Movies", CollectionMembershipRules.cleanName("🎬 Movies"))
    }

    @Test fun boundsMembershipCountAndDoesNotDiscardExistingChoices() {
        val full = (1..CollectionMembershipRules.MAX_PER_TRANSFER)
            .map { "My collection $it" }.toSet()
        assertNull(CollectionMembershipRules.add(full, "another one"))
        assertEquals(full, CollectionMembershipRules.add(full, "MY COLLECTION 1"))
        assertEquals(full.size - 1, CollectionMembershipRules.remove(full, "my collection 5").size)
    }

    @Test fun namesAreOrderedForAccessiblePredictablePresentation() {
        assertEquals(listOf("Books", "Movies", "Star Trek"),
            CollectionMembershipRules.ordered(setOf("Star Trek", "Movies", "Books")))
        assertFalse(CollectionMembershipRules.ordered(setOf("bad\u202E")).isNotEmpty())
    }
}
