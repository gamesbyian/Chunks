package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No two server accounts can share navigation authority just because their
 * torrents have the same hash. This pure test accompanies production callback
 * wiring in MainActivity; it is not a successful rTorrent refresh claim.
 */
class ExactOwningListNavigationTest {
    private val hash = "a".repeat(40)
    private val serverOne = EnvironmentId("1".repeat(64))
    private val serverTwo = EnvironmentId("2".repeat(64))
    private fun key(id: EnvironmentId) = UnifiedTransferKey.of(id, hash)

    @Test fun phoneCanOpenWithNoServerAndNeverBeRecastAsRemote() {
        assertEquals(ExactOwningListNavigation.Route.PHONE,
            ExactOwningListNavigation.route(key(EnvironmentSnapshots.PHONE), null))
        assertEquals(ExactOwningListNavigation.Route.PHONE,
            ExactOwningListNavigation.route(key(EnvironmentSnapshots.PHONE), serverOne))
    }

    @Test fun matchingSignedInServerMayNavigateButSameHashOtherAccountMayNot() {
        assertEquals(ExactOwningListNavigation.Route.AUTHENTICATED_SERVER,
            ExactOwningListNavigation.route(key(serverOne), serverOne))
        assertEquals(ExactOwningListNavigation.Route.UNAVAILABLE,
            ExactOwningListNavigation.route(key(serverTwo), serverOne))
        assertEquals(ExactOwningListNavigation.Route.UNAVAILABLE,
            ExactOwningListNavigation.route(key(serverOne), null))
    }

    @Test fun filteredOrPendingViewNeedsFullServerReadBeforeResolvingTransfer() {
        assertFalse(ExactOwningListNavigation.needsMainViewRefresh("main", "main"))
        assertTrue(ExactOwningListNavigation.needsMainViewRefresh("started", "started"))
        assertTrue(ExactOwningListNavigation.needsMainViewRefresh("main", "seeding"))
        assertTrue(ExactOwningListNavigation.needsMainViewRefresh("stopped", "main"))
    }
}
