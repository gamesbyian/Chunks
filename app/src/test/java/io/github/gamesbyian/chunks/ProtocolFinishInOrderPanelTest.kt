package io.github.gamesbyian.chunks

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.gamesbyian.chunks.rpc.ClientAddRequest
import io.github.gamesbyian.chunks.rpc.ClientCapability
import io.github.gamesbyian.chunks.rpc.ClientFile
import io.github.gamesbyian.chunks.rpc.ClientFilePriority
import io.github.gamesbyian.chunks.rpc.ClientOperations
import io.github.gamesbyian.chunks.rpc.ClientRunState
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo
import io.github.gamesbyian.chunks.rpc.ClientTorrent
import io.github.gamesbyian.chunks.rpc.TorrentClient
import io.github.gamesbyian.chunks.rpc.TorrentClientKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Finish in Order from protocol Details: the person reviews the detected
 * episode order once, and ONE verified priorities write sets the current
 * episode High (no Low on a two-band client). The policy is saved per exact
 * client identity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProtocolFinishInOrderPanelTest {
    @get:Rule val rule = createComposeRule()
    private val hash = "cd".repeat(20)

    private inner class Episodes : TorrentClient {
        override val kind = TorrentClientKind.QBITTORRENT
        val writes = mutableListOf<Map<Int, ClientFilePriority>>()
        var files = listOf(
            ClientFile(0, "Show/Show S01E02.mkv", 1_000, 0, ClientFilePriority.NORMAL),
            ClientFile(1, "Show/Show S01E01.mkv", 1_000, 0, ClientFilePriority.NORMAL),
            ClientFile(2, "Show/Show S01E03.mkv", 1_000, 0, ClientFilePriority.NORMAL))
        override suspend fun session() = ClientSessionInfo(kind, "5", "/d", ClientCapability.entries.toSet(),
            setOf(ClientFilePriority.SKIP, ClientFilePriority.NORMAL, ClientFilePriority.HIGH))
        override suspend fun torrents() = listOfNotNull(torrent(hash))
        override suspend fun torrent(hash: String) =
            ClientTorrent(hash, "Show", ClientRunState.DOWNLOADING, 3_000, 0, 0, 0, "/d")
        override suspend fun files(hash: String) = files
        override suspend fun add(request: ClientAddRequest) = Unit
        override suspend fun setStarted(hash: String, started: Boolean) = Unit
        override suspend fun remove(hash: String, deleteData: Boolean) = Unit
        override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) {
            writes += priorities
            files = files.map { f -> priorities[f.index]?.let { f.copy(priority = it) } ?: f }
        }
        override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) = Unit
        override suspend fun recheck(hash: String) = Unit
    }

    @Test fun reviewedOrderStartsWithOneVerifiedWriteAndNoLowOnATwoBandClient() {
        val client = Episodes()
        val clientId = "qb-" + System.nanoTime()
        rule.setContent {
            ChunksTheme {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                    ProtocolClientOperationsPanel(
                        hash = hash, clientLabel = "Seedbox qB", sourceClientId = clientId, observedStarted = true,
                        current = true, sourceGeneration = "fixture-generation",
                        createOperations = { ClientOperations(client, 3, 1) }, onChanged = {})
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Files").performScrollTo().performClick()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Finish in Order…").assertExists(); true }.getOrDefault(false) }
        rule.onNodeWithText("Finish in Order…").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("the current file gets High and the rest stay Normal", substring = true).assertExists()
        rule.onNodeWithText("Start Finish in Order").performScrollTo().performClick()
        rule.waitUntil(5_000) { client.writes.isNotEmpty() }
        rule.waitForIdle()
        assertEquals("one verified write", 1, client.writes.size)
        assertEquals(ClientFilePriority.HIGH, client.files.single { it.index == 1 }.priority) // E01
        assertEquals(true, client.writes.single().values.none { it == ClientFilePriority.LOW })
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNotNull("saved under the exact client identity",
            FinishInOrderStore(context, FinishInOrderScopes.protocol(clientId)).get(hash))
    }
}
