package io.github.gamesbyian.chunks

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.rpc.ClientAddRequest
import io.github.gamesbyian.chunks.rpc.ClientAddSource
import io.github.gamesbyian.chunks.rpc.ClientCapability
import io.github.gamesbyian.chunks.rpc.ClientFile
import io.github.gamesbyian.chunks.rpc.ClientFilePriority
import io.github.gamesbyian.chunks.rpc.ClientMagnets
import io.github.gamesbyian.chunks.rpc.ClientRunState
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo
import io.github.gamesbyian.chunks.rpc.ClientTorrent
import io.github.gamesbyian.chunks.rpc.ClientWriteUncertainException
import io.github.gamesbyian.chunks.rpc.RemoteClientOperations
import io.github.gamesbyian.chunks.rpc.RemoteTorrentClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** This device as the SOURCE of a job-only Copy / Move (shared port, one-use receipt). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalJobMoveSectionTest {
    @get:Rule val rule = createComposeRule()
    private val hash = "cd".repeat(20)

    /** Stands in for the Local adapter: kind null, exact hash only. */
    private inner class Device : RemoteTorrentClient {
        override val kind: RemoteBackendKind? = null
        var present = true
        var removes = 0
        var keptFiles: Boolean? = null
        override suspend fun session() = ClientSessionInfo(null, "local", "/d", ClientCapability.entries.toSet())
        override suspend fun torrents() = listOfNotNull(torrent(hash))
        override suspend fun torrent(hash: String) = if (present && hash == this@LocalJobMoveSectionTest.hash)
            ClientTorrent(hash, "n", ClientRunState.SEEDING, 1, 1, 0, 0, "/d") else null
        override suspend fun files(hash: String) = emptyList<ClientFile>()
        override suspend fun add(request: ClientAddRequest) = Unit
        override suspend fun setStarted(hash: String, started: Boolean) = Unit
        override suspend fun remove(hash: String, deleteData: Boolean) { removes++; keptFiles = !deleteData; present = false }
        override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) = Unit
        override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) = Unit
        override suspend fun recheck(hash: String) = Unit
        override suspend fun transferSource(hash: String): ClientAddSource = ClientMagnets.build(hash, "n", emptyList())
    }

    private inner class Server(private val lose: Boolean = false) : RemoteTorrentClient {
        override val kind = RemoteBackendKind.TRANSMISSION
        var adds = 0
        var started: Boolean? = null
        var present = false
        override suspend fun session() = ClientSessionInfo(kind, "4", "/srv", ClientCapability.entries.toSet())
        override suspend fun torrents() = listOfNotNull(torrent(hash))
        override suspend fun torrent(hash: String) = if (present) ClientTorrent(hash, "n",
            if (started == true) ClientRunState.DOWNLOADING else ClientRunState.STOPPED, 1, 0, 0, 0, "/srv") else null
        override suspend fun files(hash: String) = emptyList<ClientFile>()
        override suspend fun add(request: ClientAddRequest) {
            adds++
            if (lose) throw ClientWriteUncertainException("lost")
            started = request.startNow; present = true
        }
        override suspend fun setStarted(hash: String, started: Boolean) = Unit
        override suspend fun remove(hash: String, deleteData: Boolean) = Unit
        override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) = Unit
        override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) = Unit
        override suspend fun recheck(hash: String) = Unit
    }

    private fun render(device: Device, server: Server, current: Boolean = true) {
        rule.setContent {
            ChunksTheme {
                LocalJobMoveSection(
                    hash = hash, current = current, observedStarted = true, inventoryWitness = 1L,
                    createSource = { RemoteClientOperations(device, 3, 1) },
                    targets = listOf(ProtocolMoveTarget("tr", "Seedbox Transmission") { RemoteClientOperations(server, 3, 1) }),
                    onChanged = {}
                )
            }
        }
        rule.waitForIdle()
    }

    @Test fun copiesToServerThenRemovesLocalJobKeepingFilesOnlyOnSecondTap() {
        val device = Device(); val server = Server()
        render(device, server)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE).performClick()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE + ".tr").performClick()
        rule.waitUntil(5_000) { server.present }
        rule.waitForIdle()
        assertEquals(1, server.adds)
        assertEquals("observed running job starts on the target", true, server.started)
        assertEquals("local job untouched until Finish move", 0, device.removes)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE_RETIRE).performClick()
        rule.waitUntil(5_000) { !device.present }
        assertEquals(1, device.removes)
        assertEquals("session-only: files kept", true, device.keptFiles)
        assertEquals("no second Add", 1, server.adds)
    }

    @Test fun lostTargetReplyOffersNoFinishAndLocksUntilRefresh() {
        val device = Device(); val server = Server(lose = true)
        render(device, server)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE).performClick()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE + ".tr").performClick()
        rule.waitUntil(5_000) { server.adds == 1 }
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasTestTag(ChunksSemantics.PROTOCOL_MOVE_RETIRE))
            .fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE + ".tr").assertIsNotEnabled()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE + ".tr").performClick()
        rule.waitForIdle()
        assertEquals("no blind retry", 1, server.adds)
        assertEquals(0, device.removes)
    }

    @Test fun staleLocalListOffersNoCopy() {
        val device = Device(); val server = Server()
        render(device, server, current = false)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE).assertIsNotEnabled()
        assertEquals(0, server.adds)
    }
}
