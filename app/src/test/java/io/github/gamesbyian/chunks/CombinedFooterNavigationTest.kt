package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Combined uses the same persistent Transfers / Add / Options footer as the
 * phone and rTorrent lists. Footer actions only move within the view or
 * reveal view options; they never choose an Add destination or issue a
 * lifecycle command.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class CombinedFooterNavigationTest {
    @get:Rule val rule = createComposeRule()
    private val scope = "https://box.example/rutorrent2/plugins/httprpc/action.php\u0000ian"
    private val commands = ArrayList<Pair<String, Boolean>>()
    private var connections = 0
    private val gate = CombinedServerLifecycle(begin = { true }, settled = {},
        setStarted = { hash, on -> commands += hash to on
            io.github.gamesbyian.chunks.rpc.LifecycleOutcome.Verified(on) })
    private val rows = (1..30).map { i ->
        RemoteTransfer(i.toString(16).padStart(40, '0'), "Episode %02d".format(i),
            900, if (i % 2 == 0) 900 else 100, i % 3 == 0, "/d", 0, 10)
    }

    private fun show(
        pendingSharedMagnet: androidx.compose.runtime.State<Boolean> =
            androidx.compose.runtime.mutableStateOf(false),
        pendingSharedTorrent: androidx.compose.runtime.State<Boolean> =
            androidx.compose.runtime.mutableStateOf(false)
    ) = rule.setContent {
        ChunksTheme { Surface(color = ChunksColors.Background) {
            CombinedTransfersScreen(
                server = CombinedServerSource(scope, rows, stale = false, fullView = true,
                    observedAtEpochMillis = System.currentTimeMillis(), lifecycle = gate),
                savedProfiles = emptyList(), onOpenPhone = {}, onOpenServer = {}, onBack = {},
                pendingSharedMagnet = pendingSharedMagnet.value,
                pendingSharedTorrent = pendingSharedTorrent.value,
                onConnections = { connections++ })
        } }
    }

    private fun reachLastTransfer() {
        // Privacy/alias preferences load off main. The initial Combined frame
        // can have no rows yet; idle composition alone does not await that IO.
        // Retry read-only scrolling until the actual deep row is displayed.
        rule.waitUntil(15_000) {
            runCatching {
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Episode 30"))
                    .assertIsDisplayed()
            }.isSuccess
        }
    }

    @Test fun sharedTorrentDocumentRevealsDestinationAfterPriorDeepScrollWithoutAddMutation() {
        val sharedTorrent = androidx.compose.runtime.mutableStateOf(false)
        show(pendingSharedTorrent = sharedTorrent)
        reachLastTransfer()
        rule.runOnIdle { sharedTorrent.value = true }
        rule.waitForIdle()
        rule.onNodeWithText("Choose a destination for the shared .torrent").assertIsDisplayed()
        assertTrue("scrolling to document target must not run a transfer command", commands.isEmpty())
    }

    @Test fun sharedMagnetRevealsDestinationAfterPriorDeepScrollWithoutAddMutation() {
        val shared = androidx.compose.runtime.mutableStateOf(false)
        show(shared)
        reachLastTransfer()
        rule.runOnIdle { shared.value = true }
        rule.waitForIdle()
        rule.onNodeWithText("Choose a destination for the shared magnet").assertIsDisplayed()
        assertTrue("scrolling to the Add choice must not run a transfer command", commands.isEmpty())
    }

    @Test fun footerAddReturnsToExplicitDestinationsFromDeepInTheList() {
        show()
        reachLastTransfer()
        rule.onNodeWithContentDescription("Add download on this phone").assertDoesNotExist()
        rule.onNodeWithContentDescription("Choose where to add a torrent")
            .assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Add download on this phone").assertIsDisplayed()
        assertTrue("footer navigation must not issue lifecycle commands", commands.isEmpty())
    }

    @Test fun footerOptionsRevealsEnvironmentAndFilterChoices() {
        show()
        reachLastTransfer()
        rule.onNodeWithContentDescription("Options").assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Hide transfers", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Show").assertIsDisplayed()
        rule.onNodeWithContentDescription("Options").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Refine transfers", substring = true).assertIsDisplayed()
        assertTrue(commands.isEmpty())
    }

    @Test fun footerTransfersReturnsToTheTopOfTheInventory() {
        show()
        reachLastTransfer()
        rule.onNodeWithText("Transfers").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("All environments").assertIsDisplayed()
    }
    @Test fun sharedConnectionsIsReachableFromCombinedWithoutTransferCommands() {
        show()
        org.junit.Assert.assertEquals(0, connections)
        rule.onNodeWithContentDescription("Connections").assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals(1, connections)
        assertTrue(commands.isEmpty())
    }

}
