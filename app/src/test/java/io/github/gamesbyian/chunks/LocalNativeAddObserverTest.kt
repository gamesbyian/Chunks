package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddExecutor
import io.github.gamesbyian.chunks.model.UniversalAddSettlement
import io.github.gamesbyian.chunks.model.UniversalAddWriteBoundary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local's post-Add readback is the shared read-only observer, run by the
 * app-owned controller: it verifies exactly this Add's native receipt at the
 * reviewed Place, survives leaving the screen, and never writes.
 */
class LocalNativeAddObserverTest {
    private val hash = "a".repeat(40)
    private val root = File(System.getProperty("java.io.tmpdir"), "chunks-local-observer-test").apply { mkdirs() }
    private val place = LocalDownloadPlace(LocalPlaceKind.APP_STORAGE, root, "Local test", "Test-only scope")

    private fun status(token: String, stopped: Boolean = true, path: String = root.absolutePath, h: String = hash) =
        LocalTransferStatus(infoHash = h, name = "n", state = LocalTransferState.DOWNLOADING, stopped = stopped,
            progressPpm = 0, wantedBytes = 0, doneBytes = 0, payloadDownloadedBytes = 0, payloadUploadedBytes = 0,
            peers = 0, savePath = path, error = null, nativeReviewToken = token)

    private fun admitted(start: Boolean = false): Pair<LocalAddAttemptSession, UniversalAddCoordinator.Ticket> {
        val session = LocalAddAttemptSession()
        val checked = MagnetDraftCheck.inspect("magnet:?xt=urn:btih:$hash") as MagnetDraftCheck.Valid
        val intent = LocalUniversalAddAdapter.magnetIntent(checked, place, "", start, allowArtwork = false)
        assertTrue(LocalUniversalAddAdapter.review(session.coordinator, intent, true, false).accepted)
        assertTrue(session.coordinator.authorizeByUser())
        val ticket = requireNotNull(session.coordinator.beginSubmission(1L))
        session.bindPlace(ticket, place)
        return session to ticket
    }

    /** One native Add returning [result], counted: the follow-up must never call it again. */
    private class OneNativeAdd(val session: LocalAddAttemptSession, val place: LocalDownloadPlace,
                               val result: LocalAddResult) : UniversalAddExecutor<LocalAddResult> {
        val writes = AtomicInteger()
        private val inner = LocalNativeAddExecutor(session, place, { it }) { boundary, returned ->
            boundary.enter(); writes.incrementAndGet(); result.also(returned::set)
        }
        override suspend fun execute(ticket: UniversalAddCoordinator.Ticket, boundary: UniversalAddWriteBoundary) =
            inner.execute(ticket, boundary)
        override suspend fun settle(ticket: UniversalAddCoordinator.Ticket, result: LocalAddResult): UniversalAddSettlement =
            inner.settle(ticket, result)
    }

    @Test fun theAppOwnedFollowUpVerifiesThisAddsNativeReceiptWithoutAScreenOrASecondWrite() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val controller = UniversalAddAttemptController { scope }
        val (session, ticket) = admitted()
        val add = OneNativeAdd(session, place, LocalAddResult.AddedStopped(status("gen-1")))
        val reads = AtomicInteger()
        val observer = LocalNativeAddObserver(session, read = {
            // The first sample predates registration; the next lists the job.
            if (reads.incrementAndGet() == 1) emptyList() else listOf(status("gen-1"))
        }, fence = LocalScreenObservationRevision(), clock = { 5_000L + reads.get() })
        val run = controller.run(session.coordinator, ticket, add, followUp = observer,
            followUpSchedule = listOf(1L, 1L, 1L))
        assertTrue(run is io.github.gamesbyian.chunks.model.UniversalAddRun.Completed)
        assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, session.coordinator.phase)
        while (controller.isFollowingUp(EnvironmentSnapshots.LOCAL)) yield()
        assertEquals(UniversalAddCoordinator.Phase.VERIFIED, session.coordinator.phase)
        assertEquals("verified on the second sample, then the follow-up stopped", 2, reads.get())
        assertEquals(1, add.writes.get())
        assertTrue(controller.observationRevision(EnvironmentSnapshots.LOCAL) >= 1)
        scope.cancel()
    }

    @Test fun aMatchingHashWithoutThisAddsTokenIsOnlyAnExactSessionNeverAVerifiedNewAdd() = runBlocking {
        val (session, ticket) = admitted()
        session.noteNativeResult(ticket, LocalAddResult.AddedStopped(status("gen-1")), place)
        val observer = LocalNativeAddObserver(session, read = { listOf(status("someone-else")) },
            fence = LocalScreenObservationRevision(), clock = { 9_000L })
        val observation = requireNotNull(observer.observe(ticket))
        assertTrue(observation.registered)
        assertTrue(observation.reviewedPlaceVerified)
        assertFalse(observation.newRegistrationVerified)
        assertEquals(UniversalAddCoordinator.Readback.EXACT_SESSION_CONFIRMED,
            session.coordinator.reconcileExactSession(ticket, observation))
    }

    @Test fun aRequestedStartIsNotProvedWhileTheJobIsStillStopped() = runBlocking {
        val (session, ticket) = admitted(start = true)
        session.noteNativeResult(ticket, LocalAddResult.FetchingMetadata(status("gen-1")), place)
        val observation = requireNotNull(LocalNativeAddObserver(session, read = { listOf(status("gen-1")) },
            fence = LocalScreenObservationRevision(), clock = { 9_000L }).observe(ticket))
        assertEquals(false, observation.started)
        session.coordinator.reconcileExactSession(ticket, observation)
        assertEquals(UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED, session.coordinator.phase)
    }

    @Test fun aSampleThatStraddlesALocalCommandOrFailsGivesNoProof() = runBlocking {
        val (session, ticket) = admitted()
        val fence = LocalScreenObservationRevision()
        val straddling = LocalNativeAddObserver(session, read = {
            assertTrue(fence.beginMutation()); listOf(status("gen-1"))
        }, fence = fence, clock = { 9_000L })
        assertNull(straddling.observe(ticket))
        fence.settledMutation()
        assertNull("a failed native list is unknown, not an empty queue",
            LocalNativeAddObserver(session, read = { null }, fence = fence, clock = { 9_000L }).observe(ticket))
        assertTrue("isCurrent never releases the mutation latch", fence.blocksMutations())
    }

    @Test fun anotherTicketOrUnboundPlaceGivesNoProof() = runBlocking {
        val (session, ticket) = admitted()
        val (_, foreign) = admitted()
        val observer = LocalNativeAddObserver(session, read = { listOf(status("gen-1")) },
            fence = LocalScreenObservationRevision(), clock = { 9_000L })
        assertNull(observer.observe(foreign))
        assertEquals(hash, requireNotNull(observer.observe(ticket)).infoHash)
    }

    @Test fun duplicateNativeRowsAreAmbiguousNotVerified() = runBlocking {
        val (session, ticket) = admitted()
        session.noteNativeResult(ticket, LocalAddResult.AddedStopped(status("gen-1")), place)
        val observation = requireNotNull(LocalNativeAddObserver(session,
            read = { listOf(status("gen-1"), status("gen-1", path = "/elsewhere")) },
            fence = LocalScreenObservationRevision(), clock = { 9_000L }).observe(ticket))
        assertTrue(observation.registered)
        assertFalse(observation.reviewedPlaceVerified)
        assertFalse(observation.newRegistrationVerified)
    }

    @Test fun theFollowUpStopsWhenThePersonResolvesTheTicketAndNeverOverlapsAManualCheck() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val controller = UniversalAddAttemptController { scope }
        val (session, ticket) = admitted(start = true)
        session.noteNativeResult(ticket, LocalAddResult.FetchingMetadata(status("gen-1")), place)
        val reads = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val observer = LocalNativeAddObserver(session, read = {
            reads.incrementAndGet(); gate.await(); listOf(status("gen-1"))
        }, fence = LocalScreenObservationRevision(), clock = { 9_000L + reads.get() })
        assertTrue(controller.followUp(session.coordinator, observer, List(50) { 1L }))
        while (reads.get() == 0) yield()
        assertFalse("a manual check cannot overlap the follow-up's read",
            controller.recheck(session.coordinator, observer))
        gate.complete(Unit)
        while (session.coordinator.phase != UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED) yield()
        assertTrue(session.acknowledgeObservedJob())
        while (controller.isFollowingUp(EnvironmentSnapshots.LOCAL)) yield()
        val after = reads.get()
        assertNull(session.attemptedTicket)
        assertTrue("stops once the ticket is resolved, not after all 50 slots", after < 50)
        assertFalse(controller.followUp(session.coordinator, observer))
        scope.cancel()
    }

    @Test fun aHungNativeReadOccupiesOneSlotAndLaterReadsReturnNoProof() = runBlocking {
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val reader = LocalAddReadbackReader({ calls.incrementAndGet(); release.await(); emptyList() },
            timeoutMillis = 50L)
        assertNull("timed out", reader.read())
        assertNull("still occupied: no second native call is queued", reader.read())
        assertEquals(1, calls.get())
        release.countDown()
        while (true) { val r = reader.read() ?: continue; assertEquals(emptyList<LocalTransferStatus>(), r); break }
        assertEquals(2, calls.get())
    }
}
