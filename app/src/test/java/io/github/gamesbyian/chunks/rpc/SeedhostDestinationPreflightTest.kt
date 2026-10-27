package io.github.gamesbyian.chunks.rpc

import org.junit.Assert.*
import org.junit.Test

class SeedhostDestinationPreflightTest {
    private fun observed(
        path: String, complete: Boolean = true, single: Boolean? = true,
        current: Boolean = true
    ) = SeedhostDestinationPreflight.Observation(path, complete, single, current)

    @Test fun noCandidateNeverAuthorizesAnAdd() {
        assertNull(SeedhostDestinationPreflight.select(null, emptyList()))
        assertNull(SeedhostDestinationPreflight.select("/srv/downloads", listOf(
            observed("/srv/downloads", complete = false),
            observed("/srv/downloads", single = false),
            observed("/srv/downloads", current = false),
            observed("/srv/downloads", single = null)
        )))
    }

    @Test fun onlyFreshCompleteSingleFileDirectoryCanBeSelected() {
        val selection = SeedhostDestinationPreflight.select("/missing/not-verified", listOf(
            observed("/data/complete", complete = false),
            observed("/data/complete", single = false),
            observed("/data/complete", current = false),
            observed("/data/verified")
        ))
        assertEquals("/data/verified", selection?.directory)
        assertEquals(1, selection?.independentReferences)
        assertFalse(selection!!.usedConfiguredDefault)
    }

    @Test fun corroboratedConfiguredDefaultBeatsPopularity() {
        val selection = SeedhostDestinationPreflight.select("/srv/user/default", listOf(
            observed("/srv/user/another"), observed("/srv/user/another"),
            observed("/srv/user/default")
        ))
        assertEquals("/srv/user/default", selection?.directory)
        assertTrue(selection!!.usedConfiguredDefault)
        assertEquals(1, selection.independentReferences)
    }

    @Test fun popularityAndLexicalTiebreakAreDeterministic() {
        val selection = SeedhostDestinationPreflight.select(null, listOf(
            observed("/srv/b"), observed("/srv/a"), observed("/srv/b"), observed("/srv/a")
        ))
        assertEquals("/srv/a", selection?.directory)
        assertEquals(2, selection?.independentReferences)
    }

    @Test fun invalidPathsAndDangerousCommandCharactersAreIneligible() {
        val candidates = listOf("/", "/one", "/srv//child", "/srv/../other",
            "/srv/./other", "/srv/x;d.erase", "/srv/x=d.directory.set",
            "/srv/evil\\path", "/srv/quotes'bad")
        assertNull(SeedhostDestinationPreflight.select(null, candidates.map { observed(it) }))
        assertEquals("/srv/valid path",
            SeedhostDestinationPreflight.select(null, candidates.map { observed(it) } +
                observed("/srv/valid path"))?.directory)
    }
}
