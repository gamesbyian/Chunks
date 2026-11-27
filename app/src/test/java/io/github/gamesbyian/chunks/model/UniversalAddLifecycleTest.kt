package io.github.gamesbyian.chunks.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The one shared execution/observation lifecycle settles a ticket the same way
 * for every client kind. These are pure fences; real engine writes are proved
 * by the adapters' own and real-daemon tests.
 */
class UniversalAddLifecycleTest {
    private val hash = "b".repeat(40)
    private val source = UniversalAddSource.magnet("magnet:?xt=urn:btih:$hash", hash)
    private val targets = listOf(
        UniversalAddTarget.Local,
        UniversalAddTarget.AuthenticatedRemoteClient("https://host.invalid/RPC2\u0000exact-user"),
        UniversalAddTarget.ProtocolClient(EnvironmentId("qbittorrent-profile")))

    private fun ticketFor(target: UniversalAddTarget, start: Boolean = true):
        Pair<UniversalAddCoordinator, UniversalAddCoordinator.Ticket> {
        val coordinator = UniversalAddCoordinator()
        val intent = UniversalAddIntent(source, target,
            if (target === UniversalAddTarget.Local) UniversalAddPlace.Local("reviewed-place")
            else UniversalAddPlace.RemoteCustom("/reviewed"), start, allowArtwork = false)
        val caps = if (target === UniversalAddTarget.Local) EnvironmentCapabilities.local()
            else EnvironmentCapabilities.unprobed(target.environment)
        val ticket = requireNotNull(UniversalAddAdmission.authorize(coordinator, intent, caps,
            EnvironmentAvailability.LIVE, { true }, 1_000L).ticket)
        return coordinator to ticket
    }

    private fun observation(ticket: UniversalAddCoordinator.Ticket, new: Boolean, at: Long = 2_000L,
                            registered: Boolean = true) =
        UniversalAddCoordinator.ExactSessionObservation(ticket.intent.target.environment, hash, at,
            registered = registered, reviewedPlaceVerified = registered, started = registered,
            newRegistrationVerified = new)

    /** A scripted executor: what it does before/after the boundary, and how it settles. */
    private class Scripted(
        val enter: Boolean,
        val failure: Exception? = null,
        val settlement: (UniversalAddCoordinator.Ticket) -> UniversalAddSettlement =
            { UniversalAddSettlement.ReadbackRequired }
    ) : UniversalAddExecutor<String> {
        var writes = 0
        var settles = 0
        var interruptedRecorded = 0
        override suspend fun execute(ticket: UniversalAddCoordinator.Ticket, boundary: UniversalAddWriteBoundary): String {
            if (enter) { boundary.enter(); writes++ }
            failure?.let { throw it }
            return "done"
        }
        override suspend fun settle(ticket: UniversalAddCoordinator.Ticket, result: String): UniversalAddSettlement {
            settles++
            return settlement(ticket)
        }
        override fun recordInterrupted(ticket: UniversalAddCoordinator.Ticket) { interruptedRecorded++ }
    }

    @Test fun aVerifiedWriteIsVerifiedForEveryClientKind() = runBlocking {
        for (target in targets) {
            val (coordinator, ticket) = ticketFor(target)
            val executor = Scripted(enter = true, settlement = { UniversalAddSettlement.Observed(observation(it, new = true)) })
            val run = UniversalAddLifecycle.run(coordinator, ticket, executor)
            assertTrue(run is UniversalAddRun.Completed)
            assertEquals(UniversalAddCoordinator.Readback.VERIFIED, (run as UniversalAddRun.Completed).readback)
            assertEquals(UniversalAddCoordinator.Phase.VERIFIED, coordinator.phase)
            assertEquals(1, executor.writes)
        }
    }

    @Test fun aResultThatNeverCrossedTheBoundaryIsAKnownNoWriteWithoutSettlement() = runBlocking {
        for (target in targets) {
            val (coordinator, ticket) = ticketFor(target)
            val executor = Scripted(enter = false,
                settlement = { UniversalAddSettlement.Observed(observation(it, new = true)) })
            val run = UniversalAddLifecycle.run(coordinator, ticket, executor) as UniversalAddRun.Completed
            assertEquals(UniversalAddSettlement.KnownNoWrite, run.settlement)
            assertFalse(run.writeMayHaveHappened)
            // The executor cannot claim proof for a write that never happened.
            assertEquals(0, executor.settles)
            assertEquals(UniversalAddCoordinator.Phase.REVIEWED, coordinator.phase)
        }
    }

    @Test fun failuresBeforeTheBoundaryDisarmButAfterItKeepTheFence() = runBlocking {
        for (target in targets) {
            val (before, beforeTicket) = ticketFor(target)
            val early = UniversalAddLifecycle.run(before, beforeTicket, Scripted(enter = false, failure = IllegalStateException()))
            assertEquals(UniversalAddRun.Failed(beforeTicket, false), early)
            assertEquals(UniversalAddCoordinator.Phase.REVIEWED, before.phase)

            val (after, afterTicket) = ticketFor(target)
            val lost = Scripted(enter = true, failure = java.io.IOException("reply lost"))
            val late = UniversalAddLifecycle.run(after, afterTicket, lost)
            assertEquals(UniversalAddRun.Failed(afterTicket, true), late)
            assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, after.phase)
            assertEquals("an adapter receipt may be recorded", 1, lost.interruptedRecorded)
            // The fence cannot be bypassed by reviewing the same source again.
            assertFalse(after.review(afterTicket.intent, EnvironmentCapabilities.local(),
                EnvironmentAvailability.LIVE).accepted)
        }
    }

    @Test fun cancellationAfterTheBoundaryKeepsTheFenceAndPropagates() = runBlocking {
        for (target in targets) {
            val (coordinator, ticket) = ticketFor(target)
            try {
                UniversalAddLifecycle.run(coordinator, ticket, Scripted(enter = true, failure = CancellationException("left")))
                fail("cancellation must propagate")
            } catch (_: CancellationException) { }
            assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, coordinator.phase)

            val (early, earlyTicket) = ticketFor(target)
            try {
                UniversalAddLifecycle.run(early, earlyTicket, Scripted(enter = false, failure = CancellationException("left")))
                fail("cancellation must propagate")
            } catch (_: CancellationException) { }
            assertEquals(UniversalAddCoordinator.Phase.REVIEWED, early.phase)
        }
    }

    /** Invariant control `add-cancel-after-settle-skips-readback`. */
    @Test fun cancellationWhileSettlingADispatchedWriteKeepsTheFenceAndPropagates() = runBlocking {
        for (target in targets) {
            val (coordinator, ticket) = ticketFor(target)
            try {
                UniversalAddLifecycle.run(coordinator, ticket,
                    Scripted(enter = true, settlement = { throw CancellationException("left while settling") }))
                fail("cancellation must propagate")
            } catch (_: CancellationException) { }
            assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, coordinator.phase)
        }
    }

    @Test fun anUnclassifiableOrRefusedSettlementIsHandledConservatively() = runBlocking {
        val (thrown, thrownTicket) = ticketFor(targets[2])
        UniversalAddLifecycle.run(thrown, thrownTicket,
            Scripted(enter = true, settlement = { throw IllegalStateException("bad readback") }))
        assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, thrown.phase)

        val (refused, refusedTicket) = ticketFor(targets[2])
        UniversalAddLifecycle.run(refused, refusedTicket,
            Scripted(enter = true, settlement = { UniversalAddSettlement.KnownNoWrite }))
        assertEquals(UniversalAddCoordinator.Phase.REVIEWED, refused.phase)
    }

    @Test fun aTicketRunsAtMostOnce() = runBlocking {
        val (coordinator, ticket) = ticketFor(targets[1])
        val executor = Scripted(enter = true)
        UniversalAddLifecycle.run(coordinator, ticket, executor)
        assertTrue(runCatching { UniversalAddLifecycle.run(coordinator, ticket, executor) }.isFailure)
        assertEquals(1, executor.writes)
    }

    @Test fun recheckIsReadOnlyAndCannotAttributeTheAdd() = runBlocking {
        for (target in targets) {
            val (coordinator, ticket) = ticketFor(target)
            val executor = Scripted(enter = true)
            UniversalAddLifecycle.run(coordinator, ticket, executor)
            assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, coordinator.phase)
            var reads = 0
            // No proof: the fence stays.
            assertNull(UniversalAddLifecycle.recheck(coordinator) { reads++; null })
            assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, coordinator.phase)
            // An exact, authoritative absence keeps the fence: never a second write.
            assertEquals(UniversalAddCoordinator.Readback.NOT_OBSERVED, UniversalAddLifecycle.recheck(coordinator) {
                reads++; observation(it, new = false, at = 2_500L, registered = false) })
            // Exact job, Place and Start: confirmed, but never attributed to this Add.
            assertEquals(UniversalAddCoordinator.Readback.EXACT_SESSION_CONFIRMED,
                UniversalAddLifecycle.recheck(coordinator) { reads++; observation(it, new = false, at = 3_000L) })
            assertEquals(UniversalAddCoordinator.Phase.SESSION_CONFIRMED, coordinator.phase)
            assertEquals(3, reads)
            assertEquals("a recheck never writes", 1, executor.writes)
            assertTrue(coordinator.acknowledgeExactSession())
            assertNull("nothing left to check", UniversalAddLifecycle.recheck(coordinator) { reads++; null })
            assertEquals(3, reads)
        }
    }
}
