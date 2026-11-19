package io.github.gamesbyian.chunks.rpc

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An acknowledged Add is VERIFIED only when the client's own readback shows
 * the reviewed postconditions: the exact folder and the chosen Start. A job
 * that lands elsewhere or in the other run state is reported unconfirmed,
 * after exactly one write and with no corrective write or retry.
 */
class ClientAddPostconditionTest {
    private val hash = "c".repeat(40)

    private class Landing(
        val landedDirectory: String,
        val landedState: ClientRunState
    ) : TorrentClient {
        override val kind = TorrentClientKind.TRANSMISSION
        var writes = 0
        var otherWrites = 0
        private var added: ClientAddRequest? = null
        override suspend fun session() = ClientSessionInfo(kind, "4.0", "/srv/default",
            ClientCapability.entries.toSet(), ClientFilePriority.entries.toSet())
        override suspend fun torrents() = listOfNotNull(added?.let { torrent(it.source.infoHash) })
        override suspend fun torrent(hash: String): ClientTorrent? = added?.takeIf { it.source.infoHash == hash }?.let {
            ClientTorrent(hash, "x", landedState, null, null, null, null, landedDirectory)
        }
        override suspend fun files(hash: String) = emptyList<ClientFile>()
        override suspend fun add(request: ClientAddRequest) { writes++; added = request }
        override suspend fun setStarted(hash: String, started: Boolean) { otherWrites++ }
        override suspend fun remove(hash: String, deleteData: Boolean) { otherWrites++ }
        override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) { otherWrites++ }
        override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) { otherWrites++ }
        override suspend fun recheck(hash: String) { otherWrites++ }
    }

    private fun magnet() = ClientAddSource.Magnet("magnet:?xt=urn:btih:$hash")

    private fun add(client: Landing, directory: String?, startNow: Boolean) = runBlocking {
        ClientOperations(client, readbackAttempts = 3, readbackDelayMillis = 1)
            .add(ClientAddRequest(magnet(), directory, startNow))
    }

    private fun assertUnconfirmed(outcome: ClientOperationOutcome, client: Landing, reason: String) {
        assertTrue("$outcome", outcome is ClientOperationOutcome.Pending)
        assertEquals(reason, (outcome as ClientOperationOutcome.Pending).reason)
        assertEquals("exactly one Add write", 1, client.writes)
        assertEquals("no corrective Start/Stop/Move", 0, client.otherWrites)
    }

    @Test fun aJobRegisteredInAnotherFolderIsNotVerified() {
        val client = Landing("/srv/elsewhere", ClientRunState.STOPPED)
        assertUnconfirmed(add(client, "/srv/chosen", startNow = false), client, "Registered in a different directory")
    }

    @Test fun aStoppedAddThatTheClientStartedIsNotVerified() {
        val client = Landing("/srv/chosen", ClientRunState.DOWNLOADING)
        assertUnconfirmed(add(client, "/srv/chosen", startNow = false), client, "Registered but running")
    }

    @Test fun aStartedAddThatStaysStoppedIsNotVerifiedAndNotRestarted() {
        val client = Landing("/srv/chosen", ClientRunState.STOPPED)
        assertUnconfirmed(add(client, "/srv/chosen", startNow = true), client, "Registered but Start not yet observed")
    }

    @Test fun theExactFolderAndStateAreVerified() {
        val client = Landing("/srv/chosen/", ClientRunState.STOPPED)
        val outcome = add(client, "/srv/chosen", startNow = false)
        assertTrue("$outcome", outcome is ClientOperationOutcome.Verified)
        assertEquals(1, client.writes)
    }
}
