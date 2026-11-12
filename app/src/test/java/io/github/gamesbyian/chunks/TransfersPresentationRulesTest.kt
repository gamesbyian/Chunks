package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.EnvironmentViewScope
import io.github.gamesbyian.chunks.model.UnifiedTransferQuery
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure Transfers presentation rules: environment toggles, compact names and `.meta` titles. */
class TransfersPresentationRulesTest {
    private val phone = EnvironmentSnapshots.PHONE
    private val a = EnvironmentId("rtorrent-a")
    private val b = EnvironmentId("rtorrent-b")
    private val all = setOf(phone, a, b)

    @Test fun togglesHideAndShowOneEnvironmentAndReturnToDynamicAll() {
        val hidden = toggleEnvironmentVisibility(EnvironmentViewScope.All, a, all)
        assertEquals(EnvironmentViewScope.Only(setOf(phone, b)), hidden)
        val none = toggleEnvironmentVisibility(toggleEnvironmentVisibility(hidden, phone, all), b, all)
        assertEquals(EnvironmentViewScope.Only(emptySet()), none)
        assertEquals(EnvironmentViewScope.Only(setOf(a)), toggleEnvironmentVisibility(none, a, all))
        // Showing every available environment again is dynamic All, so new connections join.
        assertEquals(EnvironmentViewScope.All, toggleEnvironmentVisibility(hidden, a, all))
    }

    @Test fun anExplicitEmptySelectionPersists() {
        val query = UnifiedTransferQuery(scope = EnvironmentViewScope.Only(emptySet()))
        assertEquals(query.scope, CombinedViewPreferences.decode(CombinedViewPreferences.encode(query)).scope)
    }

    @Test fun shortNamesAndMetadataPlaceholders() {
        assertEquals(PHONE_ENVIRONMENT_LABEL, environmentToggleName(phone, "anything"))
        assertEquals("ianseeds", environmentToggleName(a, "Server · ianseeds"))
        assertEquals("Magnet abcdef01… (fetching metadata)",
            compactTransferTitle("ABCDEF01" + "0".repeat(32) + ".meta"))
        assertEquals("Away We Go (2009)", compactTransferTitle("Away We Go (2009)"))
    }
}
