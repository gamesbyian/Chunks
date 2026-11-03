package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** A presentation alias never participates in authenticated command targeting. */
class CombinedFriendlyEnvironmentLabelTest {
    @Test fun exactAuthenticatedPrincipalReceivesItsSavedFriendlyName() {
        val aliasScope = "https://seed.example/rutorrent/\u0000owner"
        val id = requireNotNull(EnvironmentSnapshots.serverIdOfAliasScope(aliasScope))
        val labels = listOf(
            CombinedSavedProfile("f".repeat(64), "Other server"),
            CombinedSavedProfile(id.value, "Library seedbox")
        )
        assertEquals("Server · Library seedbox",
            connectedServerFriendlyLabel(aliasScope, id, labels))
    }

    @Test fun differentAccountOnSameServerNeverBorrowsItsFriendlyName() {
        val aliasScope = "https://seed.example/rutorrent/\u0000owner"
        val id = requireNotNull(EnvironmentSnapshots.serverIdOfAliasScope(aliasScope))
        val other = EnvironmentSnapshots.serverId("https://seed.example/rutorrent/", "other")
        assertNotEquals(id, other)
        val label = connectedServerFriendlyLabel(aliasScope, id,
            listOf(CombinedSavedProfile(other.value, "Other person's server")))
        assertEquals(serverEnvironmentLabel(aliasScope), label)
    }
}
