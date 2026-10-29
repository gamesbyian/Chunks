package io.github.gamesbyian.chunks

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.rpc.RemoteSmartConnect
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionDiagnosticsPanelInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    @Test fun progressOffersCancelAndFailureListsPathsWithNextSteps() {
        var cancels = 0
        val candidate = RemoteSmartConnect.Candidate("https://seed.example/RPC2", "Common direct SCGI")
        rule.setContent {
            MaterialTheme {
                ConnectionDiagnosticsPanel(
                    ConnectionDiagnostics(ConnectionDiagnostics.progress(2, 4, candidate)),
                    loading = true, onCancel = { cancels++ })
            }
        }
        rule.onNodeWithText("Checking 2 of 4: Common direct SCGI (/RPC2)").assertExists()
        rule.onNodeWithText("Cancel connection check").performClick()
        assertEquals(1, cancels)
    }

    @Test fun failedRunShowsWhatWasTriedWithoutCancel() {
        rule.setContent {
            MaterialTheme {
                ConnectionDiagnosticsPanel(
                    ConnectionDiagnostics(attempts = listOf(RemoteSmartConnect.Attempt(
                        "Common direct SCGI", "/RPC2", "No rTorrent gateway here (HTTP 404)")),
                        advice = listOf("Paste the full address.")),
                    loading = false, onCancel = {})
            }
        }
        rule.onNodeWithText("• Common direct SCGI (/RPC2): No rTorrent gateway here (HTTP 404)").assertExists()
        rule.onNodeWithText("• Paste the full address.").assertExists()
        rule.onNodeWithText("Cancel connection check").assertDoesNotExist()
    }
}
