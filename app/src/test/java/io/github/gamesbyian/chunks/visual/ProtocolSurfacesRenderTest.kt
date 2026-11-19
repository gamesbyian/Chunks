package io.github.gamesbyian.chunks.visual

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import io.github.gamesbyian.chunks.ChunksSemantics
import io.github.gamesbyian.chunks.ChunksTheme
import io.github.gamesbyian.chunks.PendingLocalAdd
import io.github.gamesbyian.chunks.ProtocolAddAttempt
import io.github.gamesbyian.chunks.ProtocolAddScreen
import io.github.gamesbyian.chunks.ProtocolClientOperationsPanel
import io.github.gamesbyian.chunks.ProtocolMoveTarget
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.rpc.ClientAddRequest
import io.github.gamesbyian.chunks.rpc.ClientCapability
import io.github.gamesbyian.chunks.rpc.ClientFile
import io.github.gamesbyian.chunks.rpc.ClientFilePriority
import io.github.gamesbyian.chunks.rpc.ClientMagnets
import io.github.gamesbyian.chunks.rpc.ClientRunState
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo
import io.github.gamesbyian.chunks.rpc.ClientTorrent
import io.github.gamesbyian.chunks.rpc.ClientOperations
import io.github.gamesbyian.chunks.rpc.TorrentClient
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Actual-render inspection of the multi-protocol surfaces (Details controls
 * with Move, protocol Add review) at a typical phone width and at 320dp with
 * 200% text: every clickable is >= 48dp tall and nothing is clipped wider
 * than the screen. PNGs land in build/chunks-visual for human review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h1400dp-mdpi")
class ProtocolSurfacesRenderTest {
    @get:Rule val rule = createComposeRule()
    private val hash = "ab".repeat(20)

    private class Fake : TorrentClient {
        override val kind = io.github.gamesbyian.chunks.rpc.TorrentClientKind.QBITTORRENT
        override suspend fun session() = ClientSessionInfo(kind, "5.1", "/home/user/downloads",
            ClientCapability.entries.toSet(), setOf(ClientFilePriority.SKIP, ClientFilePriority.NORMAL, ClientFilePriority.HIGH))
        override suspend fun torrents() = emptyList<ClientTorrent>()
        override suspend fun torrent(hash: String) = ClientTorrent(hash, "Example", ClientRunState.STOPPED, 1, 0, 0, 0, "/d")
        override suspend fun files(hash: String) = listOf(
            ClientFile(0, "Example/episode-01.mkv", 1_000_000, 0, ClientFilePriority.NORMAL),
            ClientFile(1, "Example/subtitles.srt", 20_000, 0, ClientFilePriority.SKIP))
        override suspend fun add(request: ClientAddRequest) = Unit
        override suspend fun setStarted(hash: String, started: Boolean) = Unit
        override suspend fun remove(hash: String, deleteData: Boolean) = Unit
        override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) = Unit
        override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) = Unit
        override suspend fun recheck(hash: String) = Unit
        override suspend fun transferSource(hash: String) = ClientMagnets.build(hash, "Example", emptyList())
    }

    private fun show(widthDp: Int, scale: Float, content: @Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                ChunksTheme { Surface { Box(Modifier.width(widthDp.dp)) { content() } } }
            }
        }
        rule.waitForIdle()
    }

    private fun assertTouchTargetsAndWidth(widthDp: Int) {
        val nodes = rule.onAllNodesOfClickables()
        assertTrue("expected controls", nodes.isNotEmpty())
        nodes.forEach { b ->
            assertTrue("control ${b} shorter than 48dp", b.bottom - b.top >= 47.5.dp)
            assertTrue("control ${b} wider than the screen", b.right <= widthDp.dp + 0.5.dp)
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesOfClickables() =
        onAllNodes(hasClickAction()).fetchSemanticsNodes().indices.map {
            onAllNodes(hasClickAction())[it].getUnclippedBoundsInRoot()
        }

    private fun panel(width: Int, scale: Float, name: String, expand: String = ChunksSemantics.PROTOCOL_MOVE) {
        show(width, scale) {
            ProtocolClientOperationsPanel(
                hash = hash, clientLabel = "Seedbox qBittorrent", sourceClientId = "qb",
                observedStarted = false, current = true, inventoryWitness = 1L,
                createOperations = { ClientOperations(Fake(), 3, 1) }, onChanged = {},
                moveTargets = listOf(ProtocolMoveTarget("de", "Seedbox Deluge") { ClientOperations(Fake(), 3, 1) })
            )
        }
        rule.onNodeWithTag(expand).performClick()
        rule.waitForIdle()
        assertTouchTargetsAndWidth(width)
        rule.onRoot().captureRoboImage("build/chunks-visual/protocol-details-$name.png")
    }

    @Test fun detailsControlsWithMove412() = panel(412, 1f, "412-100pct")

    @Config(sdk = [35], qualifiers = "w320dp-h1800dp-mdpi")
    @Test fun detailsControlsWithMove320At200Percent() = panel(320, 2f, "320-200pct")

    @Test fun detailsChangeFolder412() = panel(412, 1f, "folder-412-100pct", ChunksSemantics.PROTOCOL_SET_LOCATION)

    @Config(sdk = [35], qualifiers = "w320dp-h1800dp-mdpi")
    @Test fun detailsChangeFolder320At200Percent() =
        panel(320, 2f, "folder-320-200pct", ChunksSemantics.PROTOCOL_SET_LOCATION)

    private fun add(width: Int, scale: Float, name: String) {
        show(width, scale) {
            ProtocolAddScreen(
                profileId = "qb", clientLabel = "Seedbox qBittorrent",
                createOperations = { ClientOperations(Fake(), 3, 1) },
                destinationPicker = {},
                sourceDraft = mutableStateOf("magnet:?xt=urn:btih:$hash&dn=Example"),
                reviewedTorrent = mutableStateOf<PendingLocalAdd?>(null),
                startState = mutableStateOf(false), attempt = ProtocolAddAttempt(),
                attemptScope = MainScope(), onViewTransfer = {}
            )
        }
        assertTouchTargetsAndWidth(width)
        rule.onRoot().captureRoboImage("build/chunks-visual/protocol-add-$name.png")
    }

    @Test fun protocolAdd412() = add(412, 1f, "412-100pct")

    @Config(sdk = [35], qualifiers = "w320dp-h1800dp-mdpi")
    @Test fun protocolAdd320At200Percent() = add(320, 2f, "320-200pct")
    private fun addWithFileChoices(width: Int, scale: Float, name: String) {
        val bytes = io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder.build(
            io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder.Request("Example Season", listOf(
                io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder.Entry(
                    listOf("Season 1", "Example.S01E01.a-rather-long-episode-name.mkv"), 40_000L) {
                    ByteArray(40_000).inputStream() },
                io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder.Entry(listOf("subtitles.srt"), 2_000L) {
                    ByteArray(2_000) { 1 }.inputStream() }),
                trackerTiers = emptyList(), pieceLength = 16 * 1024)).torrent
        show(width, scale) {
            ProtocolAddScreen(
                profileId = "qb", clientLabel = "Seedbox qBittorrent",
                createOperations = { ClientOperations(Fake(), 3, 1) },
                destinationPicker = {},
                sourceDraft = mutableStateOf(""),
                reviewedTorrent = mutableStateOf<PendingLocalAdd?>(PendingLocalAdd(
                    io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader.preview(bytes), bytes)),
                startState = mutableStateOf(false), attempt = ProtocolAddAttempt(),
                attemptScope = MainScope(), onViewTransfer = {}
            )
        }
        rule.onNodeWithTag(ChunksSemantics.ADD_FILE_CHOICE).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.ADD_FILE_PRIORITY + ".0").performClick()
        rule.waitForIdle()
        assertTouchTargetsAndWidth(width)
        rule.onRoot().captureRoboImage("build/chunks-visual/protocol-add-files-$name.png")
    }

    @Test fun protocolAddFilePriorities412() = addWithFileChoices(412, 1f, "412-100pct")

    @Config(sdk = [35], qualifiers = "w320dp-h1800dp-mdpi")
    @Test fun protocolAddFilePriorities320At200Percent() = addWithFileChoices(320, 2f, "320-200pct")
    @Config(sdk = [35], qualifiers = "w320dp-h2400dp-mdpi")
    @Test fun protocolFinishInOrderReview320At200Percent() {
        val episodes = object : TorrentClient by Fake() {
            override suspend fun files(hash: String) = listOf(
                ClientFile(0, "Example/Example S01E02.mkv", 1_000_000, 0, ClientFilePriority.NORMAL),
                ClientFile(1, "Example/Example S01E01.mkv", 1_000_000, 0, ClientFilePriority.NORMAL),
                ClientFile(2, "Example/Example S01E03.mkv", 1_000_000, 0, ClientFilePriority.NORMAL))
        }
        show(320, 2f) {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                ProtocolClientOperationsPanel(
                    hash = hash, clientLabel = "Seedbox qBittorrent", sourceClientId = "qb-render",
                    observedStarted = true, current = true, inventoryWitness = 1L,
                    createOperations = { ClientOperations(episodes, 3, 1) }, onChanged = {})
            }
        }
        rule.onNodeWithText("Files").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Finish in Order…").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Finish in Order…").performScrollTo().performClick()
        rule.waitForIdle()
        assertTouchTargetsAndWidth(320)
        rule.onRoot().captureRoboImage("build/chunks-visual/protocol-finish-in-order-320-200pct.png")
    }
    private fun addReceipt(name: String, lose: Boolean) {
        val written = mutableListOf<ClientAddRequest>()
        val client = object : TorrentClient by Fake() {
            override suspend fun add(request: ClientAddRequest) {
                if (lose) throw io.github.gamesbyian.chunks.rpc.ClientWriteUncertainException("lost")
                written += request
            }
            override suspend fun torrent(hash: String) = written.lastOrNull()?.let {
                ClientTorrent(hash, "Example", ClientRunState.STOPPED, null, 0, 0, 0, "/home/user/downloads")
            }
        }
        val attempt = ProtocolAddAttempt()
        show(320, 2f) {
            ProtocolAddScreen(
                profileId = "qb", clientLabel = "Seedbox qBittorrent",
                createOperations = { ClientOperations(client, 3, 1) },
                destinationPicker = {},
                sourceDraft = mutableStateOf("magnet:?xt=urn:btih:$hash&dn=Example"),
                reviewedTorrent = mutableStateOf<PendingLocalAdd?>(null),
                startState = mutableStateOf(false), attempt = attempt,
                attemptScope = androidx.compose.runtime.rememberCoroutineScope(), onViewTransfer = {}
            )
        }
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().performClick()
        rule.waitUntil(5_000) { attempt.outcome != null }
        rule.waitForIdle()
        assertTouchTargetsAndWidth(320)
        rule.onRoot().captureRoboImage("build/chunks-visual/protocol-add-receipt-$name-320-200pct.png")
    }

    @Config(sdk = [35], qualifiers = "w320dp-h2000dp-mdpi")
    @Test fun protocolAddVerifiedReceipt320At200Percent() = addReceipt("verified", lose = false)

    @Config(sdk = [35], qualifiers = "w320dp-h2000dp-mdpi")
    @Test fun protocolAddUncertainReceipt320At200Percent() = addReceipt("uncertain", lose = true)
}
