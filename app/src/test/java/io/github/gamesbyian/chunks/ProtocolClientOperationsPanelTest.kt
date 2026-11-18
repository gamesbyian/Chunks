package io.github.gamesbyian.chunks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.rpc.TorrentClientKind
import io.github.gamesbyian.chunks.rpc.ClientAddRequest
import io.github.gamesbyian.chunks.rpc.ClientCapability
import io.github.gamesbyian.chunks.rpc.ClientFile
import io.github.gamesbyian.chunks.rpc.ClientFilePriority
import io.github.gamesbyian.chunks.rpc.ClientRunState
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo
import io.github.gamesbyian.chunks.rpc.ClientTorrent
import io.github.gamesbyian.chunks.rpc.ClientWriteUncertainException
import io.github.gamesbyian.chunks.rpc.ClientOperations
import io.github.gamesbyian.chunks.rpc.TorrentClient
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertTextContains
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProtocolClientOperationsPanelTest {
    @get:Rule val rule = createComposeRule()
    private val hash = "ab".repeat(20)

    private inner class Fake : TorrentClient {
        override val kind = TorrentClientKind.QBITTORRENT
        var state = ClientRunState.STOPPED
        var present = true
        var writes = 0
        var lose = false
        var dir = "/d"
        var movedData: Boolean? = null
        override suspend fun session() = ClientSessionInfo(kind, "5", "/d", ClientCapability.entries.toSet())
        override suspend fun torrents() = listOfNotNull(torrent(hash))
        override suspend fun torrent(hash: String) =
            if (present && hash == this@ProtocolClientOperationsPanelTest.hash)
                ClientTorrent(hash, "n", state, 1, 0, 0, 0, dir) else null
        override suspend fun files(hash: String) = listOf(ClientFile(0, "a", 1, 0, ClientFilePriority.NORMAL))
        private fun write(apply: () -> Unit) {
            writes++
            if (lose) throw ClientWriteUncertainException("lost")
            apply()
        }
        override suspend fun add(request: ClientAddRequest) = write {}
        override suspend fun setStarted(hash: String, started: Boolean) =
            write { state = if (started) ClientRunState.DOWNLOADING else ClientRunState.STOPPED }
        override suspend fun remove(hash: String, deleteData: Boolean) = write { present = false }
        override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) = write {}
        override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) =
            write { dir = directory; movedData = moveData }
        override suspend fun recheck(hash: String) = write {}
        override suspend fun transferSource(hash: String) =
            io.github.gamesbyian.chunks.rpc.ClientMagnets.build(hash, "n", emptyList())
    }

    private inner class Target : TorrentClient {
        override val kind = TorrentClientKind.DELUGE
        var adds = 0
        var present = false
        override suspend fun session() = ClientSessionInfo(kind, "2", "/d", ClientCapability.entries.toSet())
        override suspend fun torrents() = listOfNotNull(torrent(hash))
        override suspend fun torrent(hash: String) = if (present) ClientTorrent(hash, "n",
            ClientRunState.STOPPED, 1, 0, 0, 0, "/d") else null
        override suspend fun files(hash: String) = emptyList<ClientFile>()
        override suspend fun add(request: ClientAddRequest) { adds++; present = true }
        override suspend fun setStarted(hash: String, started: Boolean) = Unit
        override suspend fun remove(hash: String, deleteData: Boolean) = Unit
        override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) = Unit
        override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) = Unit
        override suspend fun recheck(hash: String) = Unit
    }

    @Test fun moveCopiesJobThenRetiresSourceOnlyAfterSecondTap() {
        val fake = Fake()
        val target = Target()
        rule.setContent {
            ChunksTheme {
                ProtocolClientOperationsPanel(
                    hash = hash, clientLabel = "Seedbox qB", sourceClientId = "source-qb", observedStarted = false,
                    current = true, inventoryWitness = 1L,
                    createOperations = { ClientOperations(fake, 3, 1) }, onChanged = {},
                    moveTargets = listOf(ProtocolMoveTarget("t", "Box Deluge") { ClientOperations(target, 3, 1) })
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE).performClick()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE + ".t").performClick()
        rule.waitUntil(5_000) { target.present }
        rule.waitForIdle()
        rule.onNodeWithText("Job copied to Box Deluge (confirmed). Its files download there separately.").assertExists()
        assertEquals(1, target.adds)
        assertEquals("source untouched until the second tap", true, fake.present)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE_RETIRE).performClick()
        rule.waitUntil(5_000) { !fake.present }
        assertEquals(1, target.adds)
    }

    @Test fun rtorrentSourceUsesSameMoveReceiptWithoutDuplicateLifecycleControls() {
        val source = Fake()
        val destination = Target()
        rule.setContent {
            ChunksTheme {
                ProtocolClientOperationsPanel(
                    hash = hash, clientLabel = "rTorrent", sourceClientId = "rtorrent-account",
                    observedStarted = false, current = true, inventoryWitness = 1L,
                    createOperations = { ClientOperations(source, 3, 1) }, onChanged = {},
                    moveTargets = listOf(ProtocolMoveTarget("other-deluge", "Deluge") {
                        ClientOperations(destination, 3, 1)
                    }), moveOnly = true
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_START_STOP).assertDoesNotExist()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_REMOVE).assertDoesNotExist()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE).performClick()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE + ".other-deluge").performClick()
        rule.waitUntil(5_000) { destination.present }
        rule.waitForIdle()
        assertEquals("Only an explicit second tap retires the source job", true, source.present)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE_RETIRE).performClick()
        rule.waitUntil(5_000) { !source.present }
        assertEquals(1, destination.adds)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE_RETIRE).assertDoesNotExist()
    }

    private fun render(fake: Fake, current: Boolean = true) {
        rule.setContent {
            ChunksTheme {
                ProtocolClientOperationsPanel(
                    hash = hash, clientLabel = "Seedbox qB", sourceClientId = "source-qb", observedStarted = false,
                    current = current, inventoryWitness = 1L,
                    createOperations = { ClientOperations(fake, 3, 1) },
                    onChanged = {}
                )
            }
        }
        rule.waitForIdle()
    }

    @Test fun startIsConfirmedOnlyAfterExactReadback() {
        val fake = Fake()
        render(fake)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_START_STOP).performClick()
        rule.waitUntil(5_000) { fake.state == ClientRunState.DOWNLOADING }
        rule.waitForIdle()
        rule.onNodeWithText("Start: confirmed on Seedbox qB.").assertExists()
        assertEquals(1, fake.writes)
    }

    @Test fun lostReplyIsUnknownAndLocksFurtherCommands() {
        val fake = Fake().apply { lose = true }
        render(fake)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_START_STOP).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Commands are paused until the list refreshes.").assertExists()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_START_STOP).assertIsNotEnabled()
        assertEquals(1, fake.writes)
    }

    @Test fun removeKeepsFilesUnlessExplicitlyChosen() {
        val fake = Fake()
        render(fake)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_REMOVE).performClick()
        rule.onNodeWithText("Remove, keep files").assertExists()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_REMOVE_CONFIRM).performClick()
        rule.waitUntil(5_000) { !fake.present }
        rule.waitForIdle()
        rule.onNodeWithText("Remove: confirmed on Seedbox qB.").assertExists()
    }

    @Test fun staleListOffersNoCommands() {
        val fake = Fake()
        render(fake, current = false)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_START_STOP).assertIsNotEnabled()
        assertEquals(0, fake.writes)
    }

    @Test fun changeFolderShowsTheCurrentFolderDefaultsToNoFileMoveAndVerifiesByReadback() {
        val fake = Fake()
        render(fake)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_SET_LOCATION).performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasText("Current folder on Seedbox qB: /d", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The same folder, or a relative one, can't be confirmed: nothing is sent.
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_SET_LOCATION_FOLDER).performTextReplacement("/d/")
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_SET_LOCATION_CONFIRM).assertIsNotEnabled()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_SET_LOCATION_FOLDER).performTextReplacement("relative/x")
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_SET_LOCATION_CONFIRM).assertIsNotEnabled()
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_SET_LOCATION_FOLDER).performTextReplacement("/srv/new")
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_SET_LOCATION_CONFIRM).performClick()
        rule.waitUntil(5_000) { fake.dir == "/srv/new" }
        rule.waitForIdle()
        assertEquals("files are only moved on explicit request", false, fake.movedData)
        assertEquals(1, fake.writes)
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_OPERATION_STATUS).assertTextContains("confirmed", substring = true)
    }
}
