package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.EnvironmentViewScope
import io.github.gamesbyian.chunks.model.UnifiedTransferQuery
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure Transfers presentation rules: environment toggles, compact names and `.meta` titles. */
class TransfersPresentationRulesTest {
    private val local = EnvironmentSnapshots.LOCAL
    private val a = EnvironmentId("rtorrent-a")
    private val b = EnvironmentId("rtorrent-b")
    private val all = setOf(local, a, b)

    @Test fun togglesHideAndShowOneEnvironmentAndReturnToDynamicAll() {
        val hidden = toggleEnvironmentVisibility(EnvironmentViewScope.All, a, all)
        assertEquals(EnvironmentViewScope.Only(setOf(local, b)), hidden)
        val last = toggleEnvironmentVisibility(hidden, local, all)
        assertEquals(EnvironmentViewScope.Only(setOf(b)), last)
        // Reject hiding the last visible client; the interface cannot disappear.
        assertEquals(last, toggleEnvironmentVisibility(last, b, all))
        assertEquals(EnvironmentViewScope.Only(setOf(a, b)),
            toggleEnvironmentVisibility(last, a, all))
        // Showing every available environment again is dynamic All, so new connections join.
        assertEquals(EnvironmentViewScope.All, toggleEnvironmentVisibility(hidden, a, all))
    }

    @Test fun anExplicitEmptySelectionPersists() {
        val query = UnifiedTransferQuery(scope = EnvironmentViewScope.Only(emptySet()))
        assertEquals(query.scope, CombinedViewPreferences.decode(CombinedViewPreferences.encode(query)).scope)
    }

    @Test fun shortNamesAndMetadataPlaceholders() {
        assertEquals("Local", environmentToggleName(local, "anything"))
        assertEquals("ianseeds", environmentToggleName(a, "Server · ianseeds"))
        assertEquals("Magnet abcdef01… (fetching metadata)",
            compactTransferTitle("ABCDEF01" + "0".repeat(32) + ".meta"))
        assertEquals("Away We Go (2009)", compactTransferTitle("Away We Go (2009)"))
    }
}
