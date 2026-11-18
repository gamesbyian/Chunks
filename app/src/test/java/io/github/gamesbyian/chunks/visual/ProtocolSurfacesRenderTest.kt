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
import io.github.gamesbyian.chunks.ChunksSemantics
import io.github.gamesbyian.chunks.ChunksTheme
import io.github.gamesbyian.chunks.PendingLocalAdd
import io.github.gamesbyian.chunks.ProtocolAddAttempt
import io.github.gamesbyian.chunks.ProtocolAddScreen
import io.github.gamesbyian.chunks.ProtocolClientOperationsPanel
import io.github.gamesbyian.chunks.ProtocolMoveTarget
import io.github.gamesbyian.chunks.LocalJobMoveSection
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.rpc.ClientAddRequest
import io.github.gamesbyian.chunks.rpc.ClientCapability
import io.github.gamesbyian.chunks.rpc.ClientFile
import io.github.gamesbyian.chunks.rpc.ClientFilePriority
import io.github.gamesbyian.chunks.rpc.ClientMagnets
import io.github.gamesbyian.chunks.rpc.ClientRunState
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo
import io.github.gamesbyian.chunks.rpc.ClientTorrent
import io.github.gamesbyian.chunks.rpc.RemoteClientOperations
import io.github.gamesbyian.chunks.rpc.RemoteTorrentClient
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

    private class Fake : RemoteTorrentClient {
        override val kind = RemoteBackendKind.QBITTORRENT
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

    private fun panel(width: Int, scale: Float, name: String) {
        show(width, scale) {
            ProtocolClientOperationsPanel(
                hash = hash, clientLabel = "Seedbox qBittorrent", sourceClientId = "qb",
                observedStarted = false, current = true, inventoryWitness = 1L,
                createOperations = { RemoteClientOperations(Fake(), 3, 1) }, onChanged = {},
                moveTargets = listOf(ProtocolMoveTarget("de", "Seedbox Deluge") { RemoteClientOperations(Fake(), 3, 1) })
            )
        }
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE).performClick()
        rule.waitForIdle()
        assertTouchTargetsAndWidth(width)
        rule.onRoot().captureRoboImage("build/chunks-visual/protocol-details-$name.png")
    }

    @Test fun detailsControlsWithMove412() = panel(412, 1f, "412-100pct")

    @Config(sdk = [35], qualifiers = "w320dp-h1800dp-mdpi")
    @Test fun detailsControlsWithMove320At200Percent() = panel(320, 2f, "320-200pct")

    private fun localMove(width: Int, scale: Float, name: String) {
        show(width, scale) {
            LocalJobMoveSection(hash = hash, current = true, observedStarted = false, inventoryWitness = 1L,
                createSource = { RemoteClientOperations(Fake(), 3, 1) },
                targets = listOf(ProtocolMoveTarget("tr", "Seedbox Transmission") { RemoteClientOperations(Fake(), 3, 1) },
                    ProtocolMoveTarget("rt", "Seedbox rTorrent") { RemoteClientOperations(Fake(), 3, 1) }),
                onChanged = {})
        }
        rule.onNodeWithTag(ChunksSemantics.PROTOCOL_MOVE).performClick()
        rule.waitForIdle()
        assertTouchTargetsAndWidth(width)
        rule.onRoot().captureRoboImage("build/chunks-visual/local-move-$name.png")
    }

    @Test fun localMove412() = localMove(412, 1f, "412-100pct")

    @Config(sdk = [35], qualifiers = "w320dp-h1800dp-mdpi")
    @Test fun localMove320At200Percent() = localMove(320, 2f, "320-200pct")

    private fun add(width: Int, scale: Float, name: String) {
        show(width, scale) {
            ProtocolAddScreen(
                profileId = "qb", clientLabel = "Seedbox qBittorrent",
                createOperations = { RemoteClientOperations(Fake(), 3, 1) },
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
}
