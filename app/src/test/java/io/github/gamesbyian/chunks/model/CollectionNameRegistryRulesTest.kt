package io.github.gamesbyian.chunks.model

import org.junit.Assert.*
import org.junit.Test

class CollectionNameRegistryRulesTest {
    @Test fun emptyCollectionsRemainNamesEvenWithNoMembers() {
        val empty = CollectionNameRegistryRules.add(emptyList(), " Star Trek ")!!
        assertEquals(listOf("Star Trek"), empty)
        assertEquals(empty, CollectionNameRegistryRules.add(empty, "STAR TREK"))
        assertEquals(listOf("Books", "Star Trek"),
            CollectionNameRegistryRules.add(empty, "Books"))
    }

    @Test fun namesRespectValidationAndBoundedRegistry() {
        assertNull(CollectionNameRegistryRules.add(emptyList(), "bad\u202Ename"))
        assertNull(CollectionNameRegistryRules.add(emptyList(), "   "))
        val full = (1..CollectionNameRegistryRules.MAX_NAMES).map { "Collection $it" }
        assertNull(CollectionNameRegistryRules.add(full, "Another"))
        assertEquals(CollectionNameRegistryRules.MAX_NAMES,
            CollectionNameRegistryRules.add(full, "COLLECTION 1")!!.size)
    }
}
