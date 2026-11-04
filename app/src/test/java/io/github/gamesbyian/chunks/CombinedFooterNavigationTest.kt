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
    private val gate = CombinedServerLifecycle(begin = { true }, settled = {},
        setStarted = { hash, on -> commands += hash to on
            io.github.gamesbyian.chunks.rpc.LifecycleOutcome.Verified(on) })
    private val rows = (1..30).map { i ->
        RemoteTransfer(i.toString(16).padStart(40, '0'), "Episode %02d".format(i),
            900, if (i % 2 == 0) 900 else 100, i % 3 == 0, "/d", 0, 10)
    }

    private fun show() = rule.setContent {
        ChunksTheme { Surface(color = ChunksColors.Background) {
            CombinedTransfersScreen(
                server = CombinedServerSource(scope, rows, stale = false, fullView = true,
                    observedAtEpochMillis = System.currentTimeMillis(), lifecycle = gate),
                savedProfiles = emptyList(), onOpenPhone = {}, onOpenServer = {}, onBack = {})
        } }
    }

    @Test fun footerAddReturnsToExplicitDestinationsFromDeepInTheList() {
        show()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Episode 30"))
        rule.onNodeWithContentDescription("Add download on this phone").assertDoesNotExist()
        rule.onNodeWithContentDescription("Choose where to add a torrent")
            .assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Add download on this phone").assertIsDisplayed()
        assertTrue("footer navigation must not issue lifecycle commands", commands.isEmpty())
    }

    @Test fun footerOptionsRevealsEnvironmentAndFilterChoices() {
        show()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Episode 30"))
        rule.onNodeWithContentDescription("Options").assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Hide environments & filters", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Show").assertIsDisplayed()
        rule.onNodeWithContentDescription("Options").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Choose environments & filters", substring = true).assertIsDisplayed()
        assertTrue(commands.isEmpty())
    }

    @Test fun footerTransfersReturnsToTheTopOfTheInventory() {
        show()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Episode 30"))
        rule.onNodeWithText("Transfers").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("All environments").assertIsDisplayed()
    }
}
