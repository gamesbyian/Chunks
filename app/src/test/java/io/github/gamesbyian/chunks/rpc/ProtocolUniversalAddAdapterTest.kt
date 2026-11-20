package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentCapabilities
import io.github.gamesbyian.chunks.model.UniversalAddAdmission
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddLifecycle
import io.github.gamesbyian.chunks.model.UniversalAddRun
import io.github.gamesbyian.chunks.model.UniversalAddPlace
import io.github.gamesbyian.chunks.model.UniversalAddTarget
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolUniversalAddAdapterTest {
    private val hash = "ab".repeat(20)
    private val magnet = "magnet:?xt=urn:btih:$hash&dn=x"

    private class Fake(var behaviour: String = "ok") : TorrentClient {
        override val kind = TorrentClientKind.TRANSMISSION
        var added: ClientAddRequest? = null
        var writes = 0
        override suspend fun session() = ClientSessionInfo(kind, "4", "/d", ClientCapability.entries.toSet())
        override suspend fun torrents() = emptyList<ClientTorrent>()
        override suspend fun torrent(hash: String) = added?.let {
            ClientTorrent(hash, "x", if (it.startNow) ClientRunState.DOWNLOADING else ClientRunState.STOPPED,
                null, null, null, null, it.directory ?: "/d")
        }
        override suspend fun files(hash: String) = emptyList<ClientFile>()
        override suspend fun add(request: ClientAddRequest) {
            writes++
            when (behaviour) {
                "lost" -> throw ClientWriteUncertainException("lost")
                "refused" -> throw ClientRefusedException("declined")
                else -> added = request
            }
        }
        override suspend fun setStarted(hash: String, started: Boolean) = Unit
        override suspend fun remove(hash: String, deleteData: Boolean) = Unit
        override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) = Unit
        override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) = Unit
        override suspend fun recheck(hash: String) = Unit
    }

    private fun ticketFor(coordinator: UniversalAddCoordinator, request: ClientAddRequest): UniversalAddCoordinator.Ticket {
        val intent = ProtocolUniversalAddAdapter.intent("qb-profile", request, "/d")
        return requireNotNull(UniversalAddAdmission.authorize(coordinator, intent,
            EnvironmentCapabilities.unprobed(intent.target.environment), EnvironmentAvailability.LIVE,
            acquireMutationGate = { true }, submittedAtEpochMillis = 1_000L).ticket)
    }

    private fun run(behaviour: String, startNow: Boolean = false): Pair<UniversalAddCoordinator, Fake> = runBlocking {
        val fake = Fake(behaviour)
        val coordinator = UniversalAddCoordinator()
        val request = ClientAddRequest(ClientAddSource.Magnet(magnet), null, startNow)
        val ticket = ticketFor(coordinator, request)
        ProtocolUniversalAddAdapter.submit(coordinator, ticket, ClientOperations(fake, 3, 1), request) { 2_000L }
        coordinator to fake
    }

    @Test fun theIntentTargetsTheExactClientWithItsDefaultOrCustomFolder() {
        val byDefault = ProtocolUniversalAddAdapter.intent("qb-profile",
            ClientAddRequest(ClientAddSource.Magnet(magnet), null, startNow = true), "/srv/dl")
        assertTrue(byDefault.target is UniversalAddTarget.ProtocolClient)
        assertEquals("qb-profile", byDefault.target.environment.value)
        assertTrue(byDefault.place is UniversalAddPlace.ServerDefault)
        assertTrue(byDefault.startWhenAdded)
        assertTrue("Protocol artwork defaults enabled", byDefault.allowArtwork)
        assertFalse("A frozen opt-out remains off", ProtocolUniversalAddAdapter.intent("qb-profile",
            ClientAddRequest(ClientAddSource.Magnet(magnet), null, startNow = false), "/srv/dl",
            allowArtwork = false).allowArtwork)
        val custom = ProtocolUniversalAddAdapter.intent("qb-profile",
            ClientAddRequest(ClientAddSource.Magnet(magnet), "/data/tv", startNow = false), "/srv/dl")
        assertEquals("/data/tv", (custom.place as UniversalAddPlace.ServerCustom).absolutePath)
    }

    @Test fun aVerifiedAddIsVerifiedWithPositiveProvenance() {
        val (coordinator, fake) = run("ok")
        assertEquals(1, fake.writes)
        assertEquals(UniversalAddCoordinator.Phase.VERIFIED, coordinator.phase)
    }

    @Test fun aStartedVerifiedAddIsVerifiedOnlyWithStartObserved() {
        assertEquals(UniversalAddCoordinator.Phase.VERIFIED, run("ok", startNow = true).first.phase)
    }

    @Test fun aLostReplyKeepsTheAttemptedWriteFence() {
        val (coordinator, fake) = run("lost")
        assertEquals(1, fake.writes)
        assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, coordinator.phase)
    }

    @Test fun aDefiniteRefusalDisarmsBecauseNothingWasWritten() {
        val (coordinator, _) = run("refused")
        assertEquals(UniversalAddCoordinator.Phase.REVIEWED, coordinator.phase)
    }

    @Test fun rejectedArtworkPreflightCannotSendTheClientAdd() = runBlocking {
        val fake = Fake()
        val coordinator = UniversalAddCoordinator()
        val request = ClientAddRequest(ClientAddSource.Magnet(magnet), null, false)
        val ticket = ticketFor(coordinator, request)
        var checks = 0
        val result = UniversalAddLifecycle.run(coordinator, ticket,
            ProtocolAddExecutor(ClientOperations(fake, 3, 1), request,
                prepareBeforeWrite = { checks++; false }))
        assertEquals(1, checks)
        assertEquals(0, fake.writes)
        assertTrue(result is UniversalAddRun.Completed)
        assertTrue((result as UniversalAddRun.Completed).result is ClientOperationOutcome.Refused)
        assertEquals(UniversalAddCoordinator.Phase.REVIEWED, coordinator.phase)
    }

    @Test fun aRequestThatIsNotTheReviewedTicketIsRejectedBeforeAnyWrite() = runBlocking {
        val fake = Fake()
        val coordinator = UniversalAddCoordinator()
        val reviewed = ClientAddRequest(ClientAddSource.Magnet(magnet), null, startNow = false)
        val ticket = ticketFor(coordinator, reviewed)
        val other = ClientAddRequest(ClientAddSource.Magnet("magnet:?xt=urn:btih:" + "cd".repeat(20)), null, false)
        assertTrue(runCatching {
            ProtocolUniversalAddAdapter.submit(coordinator, ticket, ClientOperations(fake, 3, 1), other)
        }.isFailure)
        assertEquals(0, fake.writes)
    }
}
