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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
        rule.onNodeWithText("Open local downloads").assertDoesNotExist()
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

    @Test fun restoredNewProfileNeverBorrowsAnotherRememberedAccountsPassword() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = RememberedRemoteLogin(context)
        val roster = RememberedRemoteProfileRoster(context)
        val originalRemember = vault.rememberingEnabled()
        try {
            // Test only: an encrypted remembered account differs from the unsent new draft.
            // No network call or real server identity is claimed by this restore negative.
            assertTrue(vault.setRememberingEnabled(true))
            assertTrue(roster.clearAll())
            assertTrue(vault.save(RemoteCredentials("https://saved.invalid/RPC2", "saved", "saved-secret")))
            assertTrue(roster.saveVerified(requireNotNull(vault.load()), "Saved account"))
            connectionsFooter().performClick()
            rule.onNodeWithTag(ChunksSemantics.CONNECTION_ADD).performClick()
            rule.onNodeWithTag(ChunksSemantics.CONNECTION_NAME).performTextInput("Studio")
            rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address")
                .performTextInput("https://draft.invalid/RPC2")
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Password"))
            rule.onNodeWithText("Password").performTextInput("unsaved-secret")

            rule.activityRule.scenario.recreate()

            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Connection name (optional)"))
            rule.onNodeWithTag(ChunksSemantics.CONNECTION_NAME).assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, AnnotatedString("Studio")))
            rule.onNodeWithText("https://draft.invalid/RPC2").assertExists()
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Password"))
            rule.onNodeWithText("Password").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, AnnotatedString("")))
            rule.onNodeWithText("Finding secure rTorrent…").assertDoesNotExist()
            assertEquals("https://saved.invalid/RPC2", vault.load()?.endpoint)
            assertEquals("Saved account", roster.list().single().label)
        } finally {
            assertTrue(roster.clearAll())
            assertTrue(vault.setRememberingEnabled(false))
            assertTrue(vault.setRememberingEnabled(originalRemember))
        }
    }
}
