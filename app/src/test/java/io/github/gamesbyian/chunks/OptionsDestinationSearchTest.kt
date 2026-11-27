package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests only existing screen discovery; there is no new settings authority. Uses the real
 * [Section] values: aliases keyed by stale label text (e.g. "Server" after the section became
 * "Connections") silently stopped matching anything.
 */
class OptionsDestinationSearchTest {
    private val routes = listOf(Section.TRANSFERS, Section.LOCAL, Section.PLACES, Section.ORGANIZE,
        Section.SERVER, Section.ARCHIVES, Section.CREATE, Section.MEDIA)

    private fun find(query: String) = routes.filter { optionsDestinationMatches(it, query) }

    @Test fun blankSearchRetainsEveryExistingDestination() {
        assertEquals(routes, find("   "))
    }

    @Test fun exactDestinationAndDocumentedAliasesFindSameExistingRoutes() {
        assertEquals(listOf(Section.SERVER), find("SERVER settings"))
        assertEquals(listOf(Section.SERVER), find("remote client"))
        assertEquals(listOf(Section.PLACES), find("folder"))
        assertEquals(listOf(Section.ARCHIVES), find("archive"))
        assertEquals(listOf(Section.ORGANIZE), find("collections"))
        assertEquals(listOf(Section.MEDIA), find("poster"))
        assertTrue(optionsDestinationMatches(Section.LOCAL, "local storage"))
        assertTrue(optionsDestinationMatches(Section.TRANSFERS, "torrent list"))
    }

    @Test fun relabelledConnectionsAndLocalDestinationsKeepTheirAliases() {
        // Regression: these aliases were keyed by the old labels and matched no destination.
        assertEquals(listOf(Section.SERVER), find("endpoint"))
        assertEquals(listOf(Section.SERVER), find("login"))
        assertEquals(listOf(Section.SERVER), find("Connections"))
        assertEquals(listOf(Section.LOCAL), find("local storage"))
    }

    @Test fun unknownOrContradictoryWordsDoNotCreateFakeScreenLinks() {
        assertEquals(emptyList<Section>(), find("paywall"))
        assertFalse(optionsDestinationMatches(Section.SERVER, "local storage"))
        assertFalse(optionsDestinationMatches(Section.CREATE, "delete payload"))
    }

    @Test fun casingAndWhitespaceDoNotChangeSelectedActualDestination() {
        assertEquals(listOf(Section.SERVER), find("  CoNnEcTiOn     ACCOUNT "))
    }
}
