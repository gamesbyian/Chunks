package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.model.RemoteBackendKind
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteClientOperationsTest {
    private val hash = "0123456789abcdef0123456789abcdef01234567"

    private class Fake(
        var present: ClientTorrent?,
        val caps: Set<ClientCapability> = ClientCapability.entries.toSet(),
        val levels: Set<ClientFilePriority> = ClientFilePriority.entries.toSet()
    ) : RemoteTorrentClient {
        override val kind = RemoteBackendKind.TRANSMISSION
        var writes = 0
        var failWrite: Exception? = null
        var applyWrite: (Fake) -> Unit = {}
        var failReads = false
        var files = listOf(ClientFile(0, "a", 1, 0, ClientFilePriority.NORMAL),
            ClientFile(1, "b", 1, 0, ClientFilePriority.NORMAL))

        override suspend fun session() = ClientSessionInfo(kind, "1", "/d", caps, levels)
        override suspend fun torrents() = listOfNotNull(present)
        override suspend fun torrent(hash: String): ClientTorrent? {
            if (failReads) throw IOException("read failed")
            return present?.takeIf { it.hash == hash }
        }
        override suspend fun files(hash: String) = files
        private fun write() {
            writes++
            failWrite?.let { throw it }
            applyWrite(this)
        }
        override suspend fun add(request: ClientAddRequest) = write()
        override suspend fun setStarted(hash: String, started: Boolean) = write()
        override suspend fun remove(hash: String, deleteData: Boolean) = write()
        override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) {
            write()
            files = files.map { f -> priorities[f.index]?.let { f.copy(priority = it) } ?: f }
        }
        override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) = write()
        override suspend fun recheck(hash: String) = write()
    }

    private fun torrent(state: ClientRunState, dir: String = "/d") =
        ClientTorrent(hash, "n", state, 1, 0, 0, 0, dir)

    private fun ops(fake: Fake) = RemoteClientOperations(fake, readbackAttempts = 3, readbackDelayMillis = 1)

    @Test fun lostWriteIsUncertainAndNeverReplayed() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED)).apply {
            failWrite = ClientWriteUncertainException("lost")
        }
        val outcome = ops(fake).setStarted(hash, true)
        assertTrue(outcome is ClientOperationOutcome.Uncertain)
        assertEquals(1, fake.writes)
    }

    @Test fun anyUnexpectedWriteFailureIsUncertainNotRefused() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED)).apply { failWrite = IOException("socket") }
        assertTrue(ops(fake).setStarted(hash, true) is ClientOperationOutcome.Uncertain)
        assertEquals(1, fake.writes)
    }

    @Test fun definiteRefusalIsRefused() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED)).apply { failWrite = ClientRefusedException("no") }
        assertTrue(ops(fake).setStarted(hash, true) is ClientOperationOutcome.Refused)
    }

    @Test fun absentTargetSendsNoWrite() = runBlocking {
        val fake = Fake(null)
        assertTrue(ops(fake).remove(hash, deleteData = true) is ClientOperationOutcome.Refused)
        assertEquals(0, fake.writes)
    }

    @Test fun unreadablePreconditionSendsNoWrite() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED)).apply { failReads = true }
        assertTrue(ops(fake).setStarted(hash, true) is ClientOperationOutcome.Refused)
        assertEquals(0, fake.writes)
    }

    @Test fun acknowledgedButUnobservedIsPendingNotVerified() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED))
        val outcome = ops(fake).setStarted(hash, true)
        assertTrue(outcome is ClientOperationOutcome.Pending)
        assertEquals(1, fake.writes)
    }

    @Test fun readbackUnavailableAfterWriteIsUncertain() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED)).apply { applyWrite = { it.failReads = true } }
        assertTrue(ops(fake).setStarted(hash, true) is ClientOperationOutcome.Uncertain)
    }

    @Test fun observedPostconditionIsVerified() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED)).apply {
            applyWrite = { it.present = torrent(ClientRunState.DOWNLOADING) }
        }
        assertTrue(ops(fake).setStarted(hash, true) is ClientOperationOutcome.Verified)
    }

    @Test fun startRequestMayNotBeVerifiedByQueuedOrUnknownState() = runBlocking {
        for (state in listOf(ClientRunState.QUEUED, ClientRunState.CHECKING,
            ClientRunState.UNKNOWN, ClientRunState.ERROR)) {
            val fake = Fake(torrent(ClientRunState.STOPPED)).apply {
                applyWrite = { it.present = torrent(state) }
            }
            val outcome = ops(fake).setStarted(hash, started = true)
            assertTrue("No observed Start for $state: $outcome",
                outcome is ClientOperationOutcome.Pending)
            assertEquals(1, fake.writes)
        }
    }

    @Test fun startedAddCannotBeVerifiedIfJobIsRegisteredStopped() = runBlocking {
        val fake = Fake(null).apply { applyWrite = { it.present = torrent(ClientRunState.STOPPED) } }
        val magnet = ClientAddSource.Magnet("magnet:?xt=urn:btih:$hash")
        val outcome = ops(fake).add(ClientAddRequest(magnet, "/d", true))
        assertTrue(outcome is ClientOperationOutcome.Pending)
        assertEquals(1, fake.writes)
    }

    @Test fun startedAddCanBeVerifiedWithObservedDownloadingJob() = runBlocking {
        val fake = Fake(null).apply { applyWrite = { it.present = torrent(ClientRunState.DOWNLOADING) } }
        val magnet = ClientAddSource.Magnet("magnet:?xt=urn:btih:$hash")
        assertTrue(ops(fake).add(ClientAddRequest(magnet, "/d", true)) is
            ClientOperationOutcome.Verified)
        assertEquals(1, fake.writes)
    }

    @Test fun missingCapabilityRefusesWithoutWrite() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED), caps = setOf(ClientCapability.REMOVE))
        assertTrue(ops(fake).remove(hash, deleteData = true) is ClientOperationOutcome.Refused)
        assertEquals(0, fake.writes)
    }

    @Test fun duplicateAddRefusedBeforeWrite() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED))
        val magnet = ClientAddSource.Magnet("magnet:?xt=urn:btih:$hash")
        assertTrue(ops(fake).add(ClientAddRequest(magnet, "/d", false)) is ClientOperationOutcome.Refused)
        assertEquals(0, fake.writes)
    }

    @Test fun addInWrongDirectoryIsNotVerified() = runBlocking {
        val fake = Fake(null).apply { applyWrite = { it.present = torrent(ClientRunState.STOPPED, "/other") } }
        val magnet = ClientAddSource.Magnet("magnet:?xt=urn:btih:$hash")
        val outcome = ops(fake).add(ClientAddRequest(magnet, "/d/", false))
        assertTrue(outcome is ClientOperationOutcome.Pending)
    }

    @Test fun stoppedAddWaitsForSettledStop() = runBlocking {
        val fake = Fake(null).apply { applyWrite = { it.present = torrent(ClientRunState.CHECKING) } }
        val magnet = ClientAddSource.Magnet("magnet:?xt=urn:btih:$hash")
        assertTrue(ops(fake).add(ClientAddRequest(magnet, "/d", false)) is ClientOperationOutcome.Pending)
    }

    @Test fun prioritiesNormalizeToClientLevels() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED),
            levels = setOf(ClientFilePriority.SKIP, ClientFilePriority.NORMAL, ClientFilePriority.HIGH))
        val outcome = ops(fake).setFilePriorities(hash, mapOf(0 to ClientFilePriority.LOW, 1 to ClientFilePriority.SKIP))
        assertTrue(outcome is ClientOperationOutcome.Verified)
        assertEquals(ClientFilePriority.NORMAL, fake.files[0].priority)
    }

    @Test fun unknownFileIndexRefusedBeforeWrite() = runBlocking {
        val fake = Fake(torrent(ClientRunState.STOPPED))
        assertTrue(ops(fake).setFilePriorities(hash, mapOf(9 to ClientFilePriority.SKIP)) is ClientOperationOutcome.Refused)
        assertEquals(0, fake.writes)
    }

    @Test fun magnetBase32AndHexAgree() {
        val hex = ClientHashes.magnetInfoHash("magnet:?xt=urn:btih:$hash&dn=x")
        assertEquals(hash, hex)
        val b32 = ClientHashes.magnetInfoHash("magnet:?xt=urn:btih:AERUKZ4JVPG66AJDIVTYTK6N54ASGRLH")
        assertEquals("0123456789abcdef0123456789abcdef01234567", b32)
    }

    @Test fun qbittorrentStatesMapExplicitly() {
        assertEquals(ClientRunState.STOPPED, QBittorrentClient.stateOf("stoppedUP"))
        assertEquals(ClientRunState.STOPPED, QBittorrentClient.stateOf("pausedDL"))
        assertEquals(ClientRunState.METADATA, QBittorrentClient.stateOf("metaDL"))
        assertEquals(ClientRunState.UNKNOWN, QBittorrentClient.stateOf("newState"))
    }
}
