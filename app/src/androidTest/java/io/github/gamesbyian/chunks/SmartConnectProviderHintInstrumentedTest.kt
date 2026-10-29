package io.github.gamesbyian.chunks

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed Connect form: typing a catalogued provider host shows the honest
 * provider hint. Nothing is submitted, so no network or login occurs.
 */
@RunWith(AndroidJUnit4::class)
class SmartConnectProviderHintInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun cataloguedHostShowsDocumentedHintAndUnknownHostShowsNone() {
        val field = "Seedbox hostname, ruTorrent URL or RPC address"
        rule.onNodeWithText(field).performTextInput("box.whatbox.ca")
        rule.waitForIdle()
        rule.onNodeWithText("Recognised Whatbox.", substring = true).assertExists()
        rule.onNodeWithText("not yet tested by Chunks with this provider", substring = true).assertExists()

        rule.onNodeWithText(field).performTextReplacement("https://apollo.feralhosting.com/")
        rule.waitForIdle()
        rule.onNodeWithText("Paste your full ruTorrent address", substring = true).assertExists()

        rule.onNodeWithText(field).performTextReplacement("my-server.example")
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithText("Recognised", substring = true).fetchSemanticsNodes().size)
    }
}
