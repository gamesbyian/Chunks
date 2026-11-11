package io.github.gamesbyian.chunks

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Advanced exact RPC path on the real Connect screen (#464). Both refused
 * cases fail before any network request, so no server is needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectAdvancedPathTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun scrollTo(text: String) =
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))

    private fun enter(address: String, path: String) {
        // Every Transfers scope can reach remote Connect via the persistent footer.
        rule.onNodeWithContentDescription("Connections").assertExists().performClick()
        scrollTo("Seedbox hostname")
        rule.onNode(hasSetTextAction() and hasText("Seedbox hostname", substring = true))
            .performTextReplacement(address)
        scrollTo("Advanced connection settings")
        rule.onNodeWithText("Advanced connection settings").performClick()
        scrollTo("Exact RPC path")
        rule.onNode(hasSetTextAction() and hasText("Exact RPC path", substring = true))
            .performTextReplacement(path)
        scrollTo("Connect to rTorrent")
        rule.onNodeWithText("Connect to rTorrent").performClick()
        rule.waitForIdle()
    }

    @Test fun remoteConnectionsIsOptionalAndDeviceTransfersStayAccessible() {
        rule.onNodeWithContentDescription("Connections").assertExists().performClick()
        rule.onNodeWithText("Connect to another computer or server (optional)").assertExists()
        // Phone management belongs in Transfers, not the remote-only intro.
        rule.onNodeWithText("Your downloads").assertDoesNotExist()
        // The connection form is lazy and must still be scroll-reachable.
        scrollTo("Seedbox hostname")
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address").assertExists()
        // Visiting Connections alone never authenticates or submits a transfer.
        rule.onNodeWithText("Connection unsuccessful").assertDoesNotExist()
        // The persistent footer returns to one Combined-derived catalog without
        // requiring remote credentials or an alternate local list shortcut.
        rule.onNodeWithText("Transfers").performClick()
        // A genuinely empty first install shows the evidence-gated Welcome; any
        // other state shows the Combined-derived catalog. Both are the one canonical route.
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Welcome to Chunks").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("All environments").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun anAddressWithAPathAndAnAdvancedPathIsRefusedBeforeConnecting() {
        enter("https://host.example/rutorrent2/", "/RPC2")
        scrollTo("already includes a path")
        rule.onNodeWithText("already includes a path", substring = true).assertExists()
    }

    @Test fun anUnsafeAdvancedPathIsRefusedBeforeConnecting() {
        enter("host.example", "/a/../RPC2")
        scrollTo("starts with /")
        rule.onNodeWithText("starts with /", substring = true).assertExists()
    }
}
