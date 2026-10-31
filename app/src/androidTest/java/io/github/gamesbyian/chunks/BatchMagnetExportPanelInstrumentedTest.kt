package io.github.gamesbyian.chunks

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.model.BatchMagnetHandoff
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchMagnetExportPanelInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val rows = listOf(
        BatchMagnetHandoff.Row("a".repeat(40), "A public torrent"),
        BatchMagnetHandoff.Row("b".repeat(40), "Another public torrent")
    )

    @Test fun selectionRequiresSecondExplicitReviewAndExplainsNoPayloadMove() {
        compose.setContent {
            MaterialTheme {
                BatchMagnetExportPanel(rows, stale = false, sourceLabel = "phone")
            }
        }
        compose.onNodeWithText("Export multiple magnet identities").performClick()
        compose.onNodeWithText("Select shown (2)").performClick()
        compose.onNodeWithText("Review magnet identity export").performClick()
        compose.onNodeWithText("Share reviewed magnet identities").assertExists()
        compose.onNodeWithText("Include original titles (may expose private information)")
            .assertExists()
    }

    @Test fun staleSourceDisablesMagnetExportCompletely() {
        compose.setContent {
            MaterialTheme {
                BatchMagnetExportPanel(rows, stale = true, sourceLabel = "remote")
            }
        }
        compose.onNodeWithText("Export multiple magnet identities").assertIsNotEnabled()
    }
}
