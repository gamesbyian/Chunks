package io.github.gamesbyian.chunks

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
