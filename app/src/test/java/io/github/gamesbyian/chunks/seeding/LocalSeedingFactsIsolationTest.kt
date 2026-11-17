package io.github.gamesbyian.chunks.seeding

import io.github.gamesbyian.chunks.engine.seedingFactsBestEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** No native library or real payload is required to inject a failing JNI getter. */
class LocalSeedingFactsIsolationTest {
    @Test fun unreadableOneHandleDoesNotHideOtherValidFacts() {
        val inspected = mutableListOf<String>()
        val facts = seedingFactsBestEffort(listOf("first", "broken", "vanished", "last")) { hash ->
            inspected += hash
            when (hash) {
                "broken" -> throw IllegalStateException("simulated invalid native handle")
                "vanished" -> null
                else -> SeedingController.Facts(hash, 10L, emptyList(), false)
            }
        }
        assertEquals(listOf("first", "broken", "vanished", "last"), inspected)
        assertEquals(listOf("first", "last"), facts.map { it.infoHash })
    }

    @Test fun allUnreadableHandlesProvideNoInventedObservations() {
        val facts = seedingFactsBestEffort<String, SeedingController.Facts>(
            listOf("a", "b")
        ) { _ -> throw IllegalStateException("simulated native read failure") }
        assertTrue(facts.isEmpty())
    }
}
