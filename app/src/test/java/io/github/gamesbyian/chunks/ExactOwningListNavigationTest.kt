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

    @Test fun localCanOpenWithNoServerAndNeverBeRecastAsRemote() {
        assertEquals(ExactOwningListNavigation.Route.LOCAL,
            ExactOwningListNavigation.route(key(EnvironmentSnapshots.LOCAL), null))
        assertEquals(ExactOwningListNavigation.Route.LOCAL,
            ExactOwningListNavigation.route(key(EnvironmentSnapshots.LOCAL), serverOne))
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
    @Test fun inFlightFilteredReadQueuesOneMainViewReadWithoutReusingOldResults() {
        val fence = RemoteUiRequestFence()
        var requestedView = "started"
        var completed: String? = null
        var loading = true
        var refreshCalls = 0
        // This is the exact refreshing/queueing branch used by MainActivity,
        // with IO completion modeled deterministically, no mocked success.
        val firstTicket = fence.begin("started")
        val scheduled = ExactOwningListNavigation.requestMainIfNeeded(
            currentView = "started", requestedView = requestedView,
            setRequestedView = { requestedView = it },
            refresh = {
                refreshCalls++
                if (loading) fence.queueRefresh()
                else fence.begin(requestedView)
            }
        )
        assertTrue(scheduled)
        assertEquals("main", requestedView)
        assertEquals(1, refreshCalls)
        assertTrue(fence.current(firstTicket))
        assertTrue(fence.consumeQueuedRefresh())
        val followUp = fence.begin(requestedView)
        loading = false
        assertEquals("main", followUp.view)
        assertFalse(fence.current(firstTicket))
        assertTrue(fence.current(followUp))
        completed = followUp.view
        assertEquals("main", completed)
        assertFalse("One review tap cannot queue unlimited reads", fence.consumeQueuedRefresh())
    }

    @Test fun accountChangeInvalidatesInFlightAndQueuedMainViewRead() {
        val fence = RemoteUiRequestFence()
        var requestedView = "seeding"
        val ticketA = fence.begin("seeding")
        val routeA = ExactOwningListNavigation.route(key(serverOne), serverOne)
        assertEquals(ExactOwningListNavigation.Route.AUTHENTICATED_SERVER, routeA)
        ExactOwningListNavigation.requestMainIfNeeded("seeding", requestedView,
            setRequestedView = { requestedView = it }, refresh = fence::queueRefresh)
        assertEquals("main", requestedView)
        // Disconnect/account replacement uses this exact fence invalidation.
        fence.invalidate()
        assertFalse(fence.current(ticketA))
        assertFalse(fence.consumeQueuedRefresh())
        val ticketB = fence.begin("main")
        assertEquals(ExactOwningListNavigation.Route.UNAVAILABLE,
            ExactOwningListNavigation.route(key(serverOne), serverTwo))
        assertTrue(fence.current(ticketB))
        assertFalse(fence.current(ticketA))
    }

    @Test fun alreadyCompleteMainViewDoesNotRefetch() {
        var requests = 0
        val invoked = ExactOwningListNavigation.requestMainIfNeeded("main", "main",
            setRequestedView = { error("Must not change requested view") },
            refresh = { requests++ })
        assertFalse(invoked)
        assertEquals(0, requests)
    }

}
