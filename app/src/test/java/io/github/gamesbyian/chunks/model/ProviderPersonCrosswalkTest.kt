package io.github.gamesbyian.chunks.model

import org.junit.Assert.*
import org.junit.Test

class ProviderPersonCrosswalkTest {
    @Test fun wikidataBridgeUnifiesTmdbAndComicsCredits() {
        val tmdb = ProviderPersonId("tmdb", "person", "156223")
        val gcd = ProviderPersonId("gcd", "creator", "10447")
        val bridge = ProviderPersonCrosswalk.build(listOf(
            VerifiedPersonBridge("Q1351554", setOf(tmdb, gcd))
        ))
        assertEquals("wd:Q1351554",bridge.resolveCanonical(tmdb))
        assertEquals(bridge.resolveCanonical(tmdb),bridge.resolveCanonical(gcd))
        assertFalse(bridge.isConflicted(gcd))
        assertEquals(null,bridge.resolveCanonical(ProviderPersonId("tmdb", "person", "999999")))
    }

    @Test fun conflictingEntityOwnershipIsNotGuessed() {
        val id=ProviderPersonId("tmdb","person","156223")
        val bridge=ProviderPersonCrosswalk.build(listOf(
            VerifiedPersonBridge("Q1351554",setOf(id)),
            VerifiedPersonBridge("Q123456",setOf(id))
        ))
        assertTrue(bridge.isConflicted(id))
        assertEquals(null,bridge.resolveCanonical(id))
    }

    @Test fun cannotCollapseIdNamespacesOrPlainNames() {
        val person=ProviderPersonId("tmdb","person","42")
        val show=ProviderPersonId("tmdb","show","42")
        assertFalse(person==show)
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedPersonBridge("Peter David",setOf(person))
        }
        assertThrows(IllegalArgumentException::class.java) { ProviderPersonId("", "person", "12") }
    }
}
