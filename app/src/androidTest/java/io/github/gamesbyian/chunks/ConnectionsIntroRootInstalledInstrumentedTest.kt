package io.github.gamesbyian.chunks

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasTestTag
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

        val title = "Connect to a remote client"
        rule.onNodeWithText(title).assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNodeWithText("Your downloads").assertDoesNotExist()
        rule.onNodeWithText("Open local downloads").assertDoesNotExist()
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address").assertDoesNotExist()

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
            // Find -> reveal -> re-find -> act (TEST-ARCHITECTURE lazy-container contract).
            // Presence is a defined condition (fetchSemanticsNodes), not a caught assertion.
            // Compose exposes no dedicated type for a row disposed between lookup and its
            // focus request: it is a plain IllegalStateException, so that one case alone is
            // matched by message. It fails before any text is entered, and replacement is
            // idempotent, so re-locating is safe. Anything else propagates.
            fun enter(field: SemanticsMatcher, value: String) {
                var detached: IllegalStateException? = null
                repeat(5) {
                    rule.onNode(hasScrollToIndexAction()).performScrollToNode(field)
                    rule.waitForIdle()
                    if (rule.onAllNodes(field).fetchSemanticsNodes().isEmpty()) {
                        Thread.sleep(300)
                        return@repeat
                    }
                    try {
                        rule.onNode(field).performTextReplacement(value)
                        return
                    } catch (failure: IllegalStateException) {
                        if (failure.message?.contains("unattached node") != true) throw failure
                        detached = failure
                        Thread.sleep(300)
                    }
                }
                throw AssertionError("Could not enter a draft field (never composed, or detached each time)", detached)
            }
            enter(hasTestTag(ChunksSemantics.CONNECTION_NAME), "Studio")
            enter(hasText("Seedbox hostname, ruTorrent URL or RPC address"), "https://draft.invalid/RPC2")
            enter(hasText("Password"), "unsaved-secret")

            rule.activityRule.scenario.recreate()

            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Connection name (optional)"))
            rule.onNodeWithTag(ChunksSemantics.CONNECTION_NAME).assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, AnnotatedString("Studio")))
            // The client-type picker precedes these fields in the lazy form;
            // bring the retained draft address into composition first.
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("https://draft.invalid/RPC2"))
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
