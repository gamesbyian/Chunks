package io.github.gamesbyian.chunks

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed journey across the phone engine and All environments: an
 * acknowledged phone magnet Add that the Combined search then hides is
 * explained, and Show all brings it back. The magnet is metadata-only with an
 * unused hash and no trackers, so no payload is fetched; it is removed by
 * exact hash afterwards, keeping (empty) data.
 */
@RunWith(AndroidJUnit4::class)
class PhoneAddHiddenInCombinedInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val hash = "c0ffee" + "5".repeat(34)
    private val name = "Chunks combined hidden add fixture"

    @After fun cleanup() {
        LocalEngineHolder.current()?.removeKeepingData(hash)
    }

    private fun waitFor(ms: Long = 20_000, condition: () -> Boolean) = rule.waitUntil(ms, condition)

    private fun reachPhone() {
        val phone = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Transfers on this device")
        val entry = hasText("Open phone downloads") and hasClickAction()
        waitFor(30_000) {
            rule.onAllNodes(phone).fetchSemanticsNodes().isNotEmpty() ||
                runCatching { rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Open phone downloads")) }.isSuccess
        }
        if (rule.onAllNodes(phone).fetchSemanticsNodes().isEmpty()) {
            rule.onNode(entry).performScrollTo().performClick()
            waitFor { rule.onAllNodes(phone).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun anAcknowledgedPhoneAddHiddenByTheCombinedSearchIsExplained() {
        reachPhone()
        rule.onNodeWithText("+ Add").performClick()
        rule.waitForIdle()
        val field = hasSetTextAction() and hasText("Magnet or HTTP(S) torrent URL")
        waitFor { rule.onAllNodes(field).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(field).performScrollTo().performTextInput(
            "magnet:?xt=urn:btih:$hash&dn=" + name.replace(" ", "%20"))
        // Metadata only: nothing would download even if a peer appeared.
        val start = hasText("Start when added") and hasClickAction()
        if (rule.onNode(start).fetchSemanticsNode().config
                .getOrElseNullable(SemanticsProperties.ToggleableState) { null }?.name == "On")
            rule.onNode(start).performScrollTo().performClick()
        val addTile = hasText("Add transfer") and hasClickAction()
        waitFor { rule.onAllNodes(addTile).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(addTile).performScrollTo().performClick()
        // The engine acknowledged and read back the Add.
        waitFor(30_000) {
            rule.onAllNodes(hasText("Magnet added in metadata-only mode", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        rule.onNodeWithText("Transfers").performClick()
        rule.onNodeWithText("All environments").performScrollTo().performClick()
        // Search remains visible in the compact combined inventory.
        val search = hasSetTextAction() and hasText("Search names or hashes")
        waitFor { runCatching { rule.onNode(hasScrollToIndexAction()).performScrollToNode(search) }.isSuccess }
        rule.onNode(search).performTextInput("no transfer is called this")
        val notice = "Your new transfer on On this phone is hidden by the current search, filter or environment view."
        waitFor { runCatching { rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(notice)) }.isSuccess }
        rule.onNode(hasText("Show all transfers") and hasClickAction()).performScrollTo().performClick()
        waitFor { runCatching { rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(name, substring = true)) }.isSuccess }
        waitFor(5_000) { rule.onAllNodes(hasText(notice)).fetchSemanticsNodes().isEmpty() }
    }
}
