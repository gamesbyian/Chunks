package io.github.gamesbyian.chunks

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.rpc.TorrentClientKind
import io.github.gamesbyian.chunks.rpc.ClientAddRequest
import io.github.gamesbyian.chunks.rpc.ClientAddSource
import io.github.gamesbyian.chunks.rpc.ClientCapability
import io.github.gamesbyian.chunks.rpc.ClientFilePriority
import io.github.gamesbyian.chunks.rpc.ClientRunState
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo
import io.github.gamesbyian.chunks.rpc.ClientTorrent
import io.github.gamesbyian.chunks.rpc.ClientOperations
import io.github.gamesbyian.chunks.rpc.TorrentClient
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProtocolAddScreenTest {
    @get:Rule val rule = createComposeRule()
    private val hash = "cd".repeat(20)

    private inner class Fake : TorrentClient {
        override val kind = TorrentClientKind.DELUGE
        var added: ClientAddRequest? = null
        var writes = 0
        override suspend fun session() = ClientSessionInfo(kind, "2.1", "/srv/dl", ClientCapability.entries.toSet())
        override suspend fun torrents() = listOfNotNull(torrent(hash))
        override suspend fun torrent(hash: String): ClientTorrent? = added?.takeIf { hash == this@ProtocolAddScreenTest.hash }?.let {
            ClientTorrent(hash, "x", if (it.startNow) ClientRunState.DOWNLOADING else ClientRunState.STOPPED,
                null, null, null, null, it.directory ?: "/srv/dl")
        }
        override suspend fun files(hash: String) = emptyList<io.github.gamesbyian.chunks.rpc.ClientFile>()
        override suspend fun add(request: ClientAddRequest) { writes++; added = request }
        override suspend fun setStarted(hash: String, started: Boolean) = Unit
        override suspend fun remove(hash: String, deleteData: Boolean) = Unit
        override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) = Unit
        override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) = Unit
        override suspend fun recheck(hash: String) = Unit
    }

    @Test fun stoppedMagnetAddIsSubmittedOnceAndConfirmedByReadback() {
        val fake = Fake()
        val attempt = ProtocolAddAttempt()
        val start = mutableStateOf(false)
        val draft = mutableStateOf("")
        val torrent = mutableStateOf<PendingLocalAdd?>(null)
        rule.setContent {
            ChunksTheme {
                ProtocolAddScreen(
                    profileId = "p", clientLabel = "Box Deluge",
                    createOperations = { ClientOperations(fake, 3, 1) },
                    destinationPicker = {},
                    sourceDraft = draft, reviewedTorrent = torrent,
                    startState = start, attempt = attempt, attemptScope = androidx.compose.runtime.rememberCoroutineScope(),
                    onViewTransfer = {}
                )
            }
        }
        rule.onNodeWithTag(ChunksSemantics.ADD_SOURCE_FIELD).performTextInput("magnet:?xt=urn:btih:$hash&dn=x")
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo()
            .assertIsEnabled().performClick()
        rule.waitUntil(5_000) { attempt.outcome != null }
        rule.waitForIdle()
        rule.onNodeWithText("Add: confirmed on Box Deluge.").performScrollTo()
        assertEquals(1, fake.writes)
        assertFalse(fake.added!!.startNow)
        assertEquals(hash, (fake.added!!.source as ClientAddSource.Magnet).infoHash)
        // A settled attempt must be explicitly cleared; no second submission.
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, fake.writes)
    }

    @Test fun anotherClientsAttemptBlocksSubmission() {
        val attempt = ProtocolAddAttempt().apply { claim("other", hash) }
        val draft = mutableStateOf("magnet:?xt=urn:btih:$hash")
        rule.setContent {
            ChunksTheme {
                ProtocolAddScreen(
                    profileId = "p", clientLabel = "Box", createOperations = { ClientOperations(Fake(), 3, 1) },
                    destinationPicker = {}, sourceDraft = draft,
                    reviewedTorrent = mutableStateOf(null), startState = mutableStateOf(true),
                    attempt = attempt, attemptScope = MainScope(), onViewTransfer = {}
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().assertIsNotEnabled()
    }
}
