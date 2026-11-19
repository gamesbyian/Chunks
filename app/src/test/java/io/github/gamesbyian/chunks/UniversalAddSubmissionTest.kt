package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentCapabilities
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddExecutor
import io.github.gamesbyian.chunks.model.UniversalAddIntent
import io.github.gamesbyian.chunks.model.UniversalAddPlace
import io.github.gamesbyian.chunks.model.UniversalAddSettlement
import io.github.gamesbyian.chunks.model.UniversalAddSource
import io.github.gamesbyian.chunks.model.UniversalAddTarget
import io.github.gamesbyian.chunks.model.UniversalAddWriteBoundary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one shared submit step: admission, then at most one launched write per exact client. */
class UniversalAddSubmissionTest {
    private val hash = "e".repeat(40)
    private fun intent(profile: String) = UniversalAddIntent(
        UniversalAddSource.magnet("magnet:?xt=urn:btih:$hash", hash),
        UniversalAddTarget.ProtocolClient(EnvironmentId(profile)),
        UniversalAddPlace.ServerCustom("/dl"), startWhenAdded = false, allowArtwork = false)

    private class Gated(val release: CompletableDeferred<Unit> = CompletableDeferred()) : UniversalAddExecutor<String> {
        var writes = 0
        override suspend fun execute(ticket: UniversalAddCoordinator.Ticket, boundary: UniversalAddWriteBoundary): String {
            boundary.enter(); writes++; release.await(); return "sent"
        }
        override suspend fun settle(ticket: UniversalAddCoordinator.Ticket, result: String) = UniversalAddSettlement.ReadbackRequired
    }

    private fun submit(controller: UniversalAddAttemptController, coordinator: UniversalAddCoordinator,
                       executor: Gated, gate: () -> Boolean = { true }, freeze: () -> Unit = {}) =
        UniversalAddSubmission.admitAndLaunch(controller, coordinator, intent("deluge"),
            EnvironmentCapabilities.unprobed(EnvironmentId("deluge")), EnvironmentAvailability.LIVE,
            gate, 1L, freeze = { freeze(); executor })

    @Test fun oneLaunchFreezesFirstAndASecondClientWriteIsRefusedBeforeAnyWrite() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val controller = UniversalAddAttemptController { scope }
        val first = UniversalAddCoordinator()
        val executor = Gated()
        var frozen = 0
        val launched = submit(controller, first, executor, freeze = { frozen++ })
        assertTrue(launched is UniversalAddSubmission.Result.Launched)
        assertEquals(1, frozen)
        val second = UniversalAddCoordinator()
        val busy = submit(controller, second, Gated(CompletableDeferred(Unit)))
        assertTrue("$busy", busy is UniversalAddSubmission.Result.ClientBusy)
        assertEquals("the refused ticket never wrote", UniversalAddCoordinator.Phase.REVIEWED, second.phase)
        executor.release.complete(Unit)
        while (controller.isSubmitting(EnvironmentId("deluge"))) yield()
        assertEquals(1, executor.writes)
        scope.cancel()
    }

    @Test fun theAwaitedLocalLaneRunsOnceAndRefusesBeforeAnyWrite() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val controller = UniversalAddAttemptController { scope }
        val done = Gated(CompletableDeferred(Unit))
        var frozen = 0
        val ran = UniversalAddSubmission.admitAndRun(controller, UniversalAddCoordinator(), intent("local-lane"),
            EnvironmentCapabilities.unprobed(EnvironmentId("local-lane")), EnvironmentAvailability.LIVE,
            { true }, 1L, freeze = { frozen++; done }, followUpSchedule = emptyList())
        assertTrue("$ran", ran is UniversalAddSubmission.RunResult.Ran)
        assertEquals(1, frozen)
        assertEquals(1, done.writes)
        assertFalse("released after the awaited run", controller.isSubmitting(EnvironmentId("local-lane")))

        val coordinator = UniversalAddCoordinator()
        val never = Gated(CompletableDeferred(Unit))
        val failedFreeze = UniversalAddSubmission.admitAndRun(controller, coordinator, intent("local-lane"),
            EnvironmentCapabilities.unprobed(EnvironmentId("local-lane")), EnvironmentAvailability.LIVE,
            { true }, 2L, freeze = { _ -> error("cannot bind the Place") as Gated })
        assertTrue(failedFreeze is UniversalAddSubmission.RunResult.Refused)
        assertEquals(UniversalAddCoordinator.Phase.REVIEWED, coordinator.phase)
        val noGate = UniversalAddSubmission.admitAndRun(controller, UniversalAddCoordinator(), intent("local-lane"),
            EnvironmentCapabilities.unprobed(EnvironmentId("local-lane")), EnvironmentAvailability.LIVE,
            { false }, 3L, freeze = { never })
        assertTrue(noGate is UniversalAddSubmission.RunResult.Refused)
        assertEquals("nothing written by either refusal", 0, never.writes)
        scope.cancel()
    }

    @Test fun aRefusedGateOrAFailedFreezeLaunchesNothing() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val controller = UniversalAddAttemptController { scope }
        val executor = Gated(CompletableDeferred(Unit))
        val noGate = submit(controller, UniversalAddCoordinator(), executor, gate = { false })
        assertTrue(noGate is UniversalAddSubmission.Result.Refused)
        assertFalse((noGate as UniversalAddSubmission.Result.Refused).decision.mutationGateAcquired)
        val coordinator = UniversalAddCoordinator()
        val failedFreeze = submit(controller, coordinator, executor, freeze = { error("cannot record") })
        assertTrue(failedFreeze is UniversalAddSubmission.Result.Refused)
        assertTrue("the caller still owns the taken gate",
            (failedFreeze as UniversalAddSubmission.Result.Refused).decision.mutationGateAcquired)
        assertEquals("disarmed without a write", UniversalAddCoordinator.Phase.REVIEWED, coordinator.phase)
        assertEquals(0, executor.writes)
        assertFalse(controller.isSubmitting(EnvironmentId("deluge")))
        scope.cancel()
    }
}
