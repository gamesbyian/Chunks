package io.github.gamesbyian.chunks

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native installed-activity navigation; no rTorrent server or filesystem write. */
@RunWith(AndroidJUnit4::class)
class LocalZipOfflineNavigationInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun offlineZipReviewIsAvailableWithoutCredentialsAndReturnsToConnection() {
        // Offline tools remain available from the optional server-setup route.
        // New installations correctly start in local Transfers instead.
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Connections").performClick()
        rule.onNodeWithText("Connect to another computer or server (optional)").assertExists()
        rule.onNodeWithText("Your downloads").assertDoesNotExist()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("More offline tools"))
        rule.onNodeWithText("More offline tools").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Inspect a local ZIP without connecting"))
        rule.onNodeWithText("Inspect a local ZIP without connecting").performClick()
        rule.onNodeWithText("Local ZIP review").assertExists()
        rule.onNodeWithText("Choose local ZIP").assertExists()
        rule.onNodeWithText("Read-only limits:", substring = true).assertExists()
        rule.onNodeWithText("Back").performClick()
        // Return to the approved Connections card, not the removed local promo.
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onNodeWithText("Connect to another computer or server (optional)").assertExists()
        rule.onNodeWithText("Your downloads").assertDoesNotExist()
    }
}
