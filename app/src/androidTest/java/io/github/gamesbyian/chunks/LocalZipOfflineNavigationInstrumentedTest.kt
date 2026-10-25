package io.github.gamesbyian.chunks

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
        rule.onNodeWithText("Connect to rTorrent").assertExists()
        rule.onNodeWithText("Inspect a local ZIP without connecting").performClick()
        rule.onNodeWithText("Local ZIP review").assertExists()
        rule.onNodeWithText("Choose local ZIP").assertExists()
        rule.onNodeWithText("Read-only limits:", substring = true).assertExists()
        rule.onNodeWithText("Back").performClick()
        rule.onNodeWithText("Connect to rTorrent").assertExists()
    }
}
