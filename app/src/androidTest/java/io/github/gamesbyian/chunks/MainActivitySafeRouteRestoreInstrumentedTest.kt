package io.github.gamesbyian.chunks

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real installed Activity recreation: no Add source, credentials, login or
 * pending command is persisted by navigation restoration. Activity recreation
 * is NOT process kill, and real saved-profile/peer flows require other gates.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySafeRouteRestoreInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    // Combined can have a spoken "Transfers" section heading while the
    // footer also has a Transfers button. Require an actionable footer node
    // rather than accidentally asserting on the heading during cold probing.
    private fun footerTransfers() = rule.onNode(hasText("Transfers") and hasClickAction())

    @Test fun disconnectedCanonicalTransfersAndConnectionsSurviveActivityRecreationWithoutCommands() {
        val before = LocalEngineHolder.current()?.completeIdentityInventory().orEmpty()
        // No remote profile is required: root Transfers now uses one canonical
        // browser, whose genuine fresh welcome appears after durable probing.
        footerTransfers().assertIsSelected()
        // Purple Add remains visually prominent but must not claim selected
        // semantics unless the actual Add screen is active.
        rule.onNodeWithText("+ Add").assertIsNotSelected()
        rule.onNodeWithContentDescription("Connections").assertIsNotSelected()
        rule.activityRule.scenario.recreate()
        footerTransfers().assertExists()
        rule.onNodeWithContentDescription("Connections").performClick()
        rule.onNodeWithContentDescription("Connections").assertIsSelected()
        rule.onNodeWithText("+ Add").assertIsNotSelected()
        rule.onNodeWithText("Connect to another computer or server (optional)").assertExists()
        rule.activityRule.scenario.recreate()
        // Connections is a restorable non-mutating root, never a legacy phone
        // list or an implicitly authenticated new server.
        rule.onNodeWithText("Connect to another computer or server (optional)").assertExists()
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address").assertExists()
        footerTransfers().performClick()
        footerTransfers().assertIsSelected()
        rule.onNodeWithText("+ Add").assertIsNotSelected()
        rule.onNodeWithText("+ Add").performClick()
        rule.onNodeWithText("+ Add").assertIsSelected()
        footerTransfers().assertIsNotSelected()
        rule.onNodeWithText("Add a transfer").assertExists()
        rule.activityRule.scenario.recreate()
        // The existing Add destination remains source-empty after restoration.
        // Only the Add implementer may replace this destination with its single
        // source-first form; rotation itself never grants an approval.
        rule.onNodeWithText("Add a transfer").assertExists()
        rule.onNodeWithText("+ Add").assertIsSelected()
        rule.onNodeWithText("Start selected").assertDoesNotExist()
        org.junit.Assert.assertEquals("Restoring navigation cannot create torrent jobs",
            before, LocalEngineHolder.current()?.completeIdentityInventory().orEmpty())
    }

    @Test fun createCommandRouteRestoresOnlyCanonicalTransfersAndKeepsOfflineToolsReachable() {
        rule.onNodeWithContentDescription("Connections").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("More offline tools"))
        rule.onNodeWithText("More offline tools").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Create a torrent from phone files"))
        rule.onNodeWithText("Create a torrent from phone files").performClick()
        rule.onNodeWithText("Create torrent").assertExists()
        // Recreating never retries file picker or submission.
        rule.activityRule.scenario.recreate()
        footerTransfers().assertExists()
        rule.onNodeWithText("Create torrent").assertDoesNotExist()
        rule.onNodeWithContentDescription("Connections").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("More offline tools"))
        rule.onNodeWithText("More offline tools").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Create a torrent from phone files"))
        rule.onNodeWithText("Create a torrent from phone files").assertExists()
    }
}
