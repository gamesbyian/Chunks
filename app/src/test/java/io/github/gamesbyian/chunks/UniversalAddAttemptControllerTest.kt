package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentCapabilities
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UniversalAddAdmission
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddExecutor
import io.github.gamesbyian.chunks.model.UniversalAddIntent
import io.github.gamesbyian.chunks.model.UniversalAddPlace
import io.github.gamesbyian.chunks.model.UniversalAddRun
import io.github.gamesbyian.chunks.model.UniversalAddSettlement
import io.github.gamesbyian.chunks.model.UniversalAddSource
import io.github.gamesbyian.chunks.model.UniversalAddTarget
import io.github.gamesbyian.chunks.model.UniversalAddWriteBoundary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app-owned controller: one write and one read-only recheck per exact client. */
class UniversalAddAttemptControllerTest {
    private val hash = "c".repeat(40)

    private fun ticket(profile: String): Pair<UniversalAddCoordinator, UniversalAddCoordinator.Ticket> {
        val coordinator = UniversalAddCoordinator()
        val target = UniversalAddTarget.ProtocolClient(EnvironmentId(profile))
        val intent = UniversalAddIntent(UniversalAddSource.magnet("magnet:?xt=urn:btih:$hash", hash), target,
            UniversalAddPlace.RemoteCustom("/dl"), startWhenAdded = false, allowArtwork = false)
        return coordinator to requireNotNull(UniversalAddAdmission.authorize(coordinator, intent,
            EnvironmentCapabilities.unprobed(target.environment), EnvironmentAvailability.LIVE, { true }, 1L).ticket)
    }

    /** Writes once, then waits for [release] (so a second Add can try to overlap). */
    private class Gated(val release: CompletableDeferred<Unit> = CompletableDeferred()) : UniversalAddExecutor<String> {
        var writes = 0
        override suspend fun execute(ticket: UniversalAddCoordinator.Ticket, boundary: UniversalAddWriteBoundary): String {
            boundary.enter(); writes++; release.await(); return "sent"
        }
        override suspend fun settle(ticket: UniversalAddCoordinator.Ticket, result: String) =
            UniversalAddSettlement.ReadbackRequired
    }

    @Test fun aSecondAddForTheSameClientIsRefusedBeforeAnyWriteWhileTheFirstIsInFlight() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val controller = UniversalAddAttemptController { scope }
        val (first, firstTicket) = ticket("deluge")
        val (second, secondTicket) = ticket("deluge")
        val (other, otherTicket) = ticket("transmission")
        val executor = Gated()
        val otherExecutor = Gated(CompletableDeferred(Unit))
        var finished = 0
        assertTrue(controller.launch(first, firstTicket, executor, onFinished = { finished++ }))
        assertTrue(controller.isSubmitting(EnvironmentId("deluge")))
        assertFalse(controller.launch(second, secondTicket, Gated(CompletableDeferred(Unit))))
        assertEquals("the refused ticket never reached a write", UniversalAddCoordinator.Phase.REVIEWED, second.phase)
        // Another exact client is independent.
        assertTrue(controller.launch(other, otherTicket, otherExecutor))
        executor.release.complete(Unit)
        while (controller.isSubmitting(EnvironmentId("deluge")) || controller.isSubmitting(EnvironmentId("transmission"))) yield()
        assertEquals(1, executor.writes)
        assertEquals(1, otherExecutor.writes)
        assertEquals(1, finished)
        assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, first.phase)
        scope.cancel()
    }

    @Test fun clearingTheOwnerKeepsTheFenceAndReportsTheInterruption() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val controller = UniversalAddAttemptController { scope }
        val (coordinator, t) = ticket("qbittorrent")
        var interrupted: Boolean? = null
        var settled = false
        var finished = false
        controller.launch(coordinator, t, Gated(), onInterrupted = { interrupted = it },
            onFinished = { finished = true }) { settled = true }
        scope.cancel()
        assertEquals(true, interrupted)
        assertFalse(settled)
        assertTrue(finished)
        assertFalse(controller.isSubmitting(EnvironmentId("qbittorrent")))
        assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, coordinator.phase)
    }

    @Test fun recheckIsSingleFlightAndNeverRunsWhileAWriteIsInFlight() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val controller = UniversalAddAttemptController { scope }
        val (coordinator, t) = ticket("transmission")
        val executor = Gated()
        controller.launch(coordinator, t, executor)
        var reads = 0
        assertFalse("no recheck during the write", controller.recheck(coordinator, { reads++; null }))
        executor.release.complete(Unit)
        while (controller.isSubmitting(EnvironmentId("transmission"))) yield()
        val gate = CompletableDeferred<Unit>()
        var result: UniversalAddCoordinator.Readback? = UniversalAddCoordinator.Readback.WRONG_TICKET
        assertTrue(controller.recheck(coordinator, { reads++; gate.await(); null }) { result = it })
        assertTrue(controller.isChecking(EnvironmentId("transmission")))
        assertFalse(controller.recheck(coordinator, { reads++; null }))
        gate.complete(Unit)
        while (controller.isChecking(EnvironmentId("transmission"))) yield()
        assertNull(result)
        assertEquals(1, reads)
        assertEquals(1, executor.writes)
        assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, coordinator.phase)
        scope.cancel()
    }

    @Test fun runInsideTheCallersRetainedCoroutineUsesTheSameFence() = runBlocking {
        val controller = UniversalAddAttemptController { this }
        val (coordinator, t) = ticket("local-like")
        val run = controller.run(coordinator, t, Gated(CompletableDeferred(Unit)))
        assertTrue(run is UniversalAddRun.Completed)
        assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, coordinator.phase)
        assertFalse(controller.isSubmitting(EnvironmentId("local-like")))
    }
}
