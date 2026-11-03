package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests only existing screen discovery; there is no new settings authority. */
class OptionsDestinationSearchTest {
    private val routes = listOf(
        "Transfers", "On this phone", "Places", "Organize", "Server",
        "ZIP review", "Create"
    )

    @Test fun blankSearchRetainsEveryExistingDestination() {
        assertEquals(routes, routes.filter { optionsDestinationMatches(it, "   ") })
    }

    @Test fun exactDestinationAndDocumentedAliasesFindSameExistingRoutes() {
        assertEquals(listOf("Server"), routes.filter { optionsDestinationMatches(it, "SERVER settings") })
        assertEquals(listOf("Places"), routes.filter { optionsDestinationMatches(it, "folder") })
        assertEquals(listOf("ZIP review"), routes.filter { optionsDestinationMatches(it, "archive") })
        assertEquals(listOf("Organize"), routes.filter { optionsDestinationMatches(it, "collections") })
        assertTrue(optionsDestinationMatches("On this phone", "local storage"))
        assertTrue(optionsDestinationMatches("Transfers", "torrent list"))
    }

    @Test fun unknownOrContradictoryWordsDoNotCreateFakeScreenLinks() {
        assertEquals(emptyList<String>(), routes.filter { optionsDestinationMatches(it, "paywall") })
        assertFalse(optionsDestinationMatches("Server", "local storage"))
        assertFalse(optionsDestinationMatches("Create", "delete payload"))
    }

    @Test fun casingAndWhitespaceDoNotChangeSelectedActualDestination() {
        assertEquals(listOf("Server"), routes.filter {
            optionsDestinationMatches(it, "  CoNnEcTiOn     ACCOUNT ")
        })
    }
}
