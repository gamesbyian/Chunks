package io.github.gamesbyian.chunks

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Installed production MainActivity navigation, without fake transfer rows,
 * RPC commands or torrent submission. The four root destinations must remain
 * directly available even while another destination owns the viewport.
 *
 * Source: the owner-approved permanent Transfers/Add/Connections/Options footer.
 * This is a navigation and layout test, not a download/peer/provider receipt.
 */
@RunWith(AndroidJUnit4::class)
class PersistentRootNavigationInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun transfersButton() =
        // The root footer owns the last clickable exact "Transfers" label.
        // In-list transfer headings can be visible but are not navigation.
        rule.onAllNodes(hasText("Transfers") and hasClickAction()).onLast()

    private fun addButton() =
        rule.onNodeWithContentDescription("Choose where to add a transfer")

    private fun connectionsButton() =
        rule.onNodeWithContentDescription("Connections")

    private fun optionsButton() =
        rule.onNodeWithContentDescription("Options")

    private fun currentFooterBottom(): Float {
        transfersButton().assertIsDisplayed()
        addButton().assertIsDisplayed()
        connectionsButton().assertIsDisplayed()
        optionsButton().assertIsDisplayed()
        return optionsButton().getUnclippedBoundsInRoot().bottom.value
    }

    private fun paneIs(vararg accepted: String): Boolean = accepted.any { title ->
        rule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title),
            useUnmergedTree = true
        ).fetchSemanticsNodes().isNotEmpty()
    }

    private fun checkDestination(
        label: String, expectedPaneTitles: Array<out String>,
        firstFooterBottom: Float
    ) {
        rule.waitUntil(15_000) { paneIs(*expectedPaneTitles) }
        val bottom = currentFooterBottom()
        assertTrue(
            "$label must keep the root footer anchored, not scroll it with the content",
            abs(bottom - firstFooterBottom) <= 3f
        )
    }

    @Test fun fourDestinationsRemainDirectlyReachableWithoutBackHistory() {
        rule.waitUntil(20_000) {
            runCatching { currentFooterBottom(); true }.getOrDefault(false)
        }
        val bottom = currentFooterBottom()

        // An existing remembered remote may legitimately make Transfers a
        // Combined inventory. Neither case chooses an Add target.
        transfersButton().performClick()
        checkDestination("Transfers", arrayOf("Transfers on this device", "All environments"), bottom)

        addButton().performClick()
        checkDestination("Add", arrayOf("Add on this device", "Add"), bottom)

        optionsButton().performClick()
        checkDestination("Options", arrayOf("Options"), bottom)

        connectionsButton().performClick()
        checkDestination("Connections", arrayOf("Connections"), bottom)

        // The same footer must still work after detours, with no Back presses.
        transfersButton().performClick()
        checkDestination("Transfers after Connections",
            arrayOf("Transfers on this device", "All environments"), bottom)

        optionsButton().performClick()
        checkDestination("Options after Transfers", arrayOf("Options"), bottom)

        addButton().performClick()
        checkDestination("Add after Options", arrayOf("Add on this device", "Add"), bottom)

        connectionsButton().performClick()
        checkDestination("Connections after Add", arrayOf("Connections"), bottom)
    }
}
