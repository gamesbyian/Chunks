package io.github.gamesbyian.chunks

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed production MainActivity route after the remote-only Connections
 * introduction shipped. No credentials, network request or transfer mutation:
 * only the owner-approved root presentation and accessibility semantics.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionsIntroRootInstalledInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun connectionsFooter() = rule.onNodeWithContentDescription("Connections")

    private fun transfersFooter() =
        rule.onAllNodes(hasText("Transfers") and hasClickAction()).onLast()

    @Test fun remoteIntroAndSelectedFooterSurviveTransfersRoundTrip() {
        // Read the actual installed Activity, not an isolated mocked composable.
        rule.waitUntil(20_000) {
            runCatching {
                connectionsFooter().assertExists()
                transfersFooter().assertExists()
                true
            }.getOrDefault(false)
        }
        connectionsFooter().performClick()
        rule.waitForIdle()

        val title = "Connect to another computer or server (optional)"
        rule.onNodeWithText(title).assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNodeWithText("Your downloads").assertDoesNotExist()
        rule.onNodeWithText("Open phone downloads").assertDoesNotExist()
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address").assertExists()

        // Purple Add stays prominent as an action but is not the selected tab.
        connectionsFooter().assertIsSelected()
        rule.onNodeWithText("+ Add").assertIsNotSelected()
        transfersFooter().assertIsNotSelected()

        transfersFooter().performClick()
        transfersFooter().assertIsSelected()
        connectionsFooter().assertIsNotSelected()
        rule.onNodeWithText("+ Add").assertIsNotSelected()

        connectionsFooter().performClick()
        connectionsFooter().assertIsSelected()
        rule.onNodeWithText(title).assertExists()
        rule.onNodeWithText("Your downloads").assertDoesNotExist()
    }
}
