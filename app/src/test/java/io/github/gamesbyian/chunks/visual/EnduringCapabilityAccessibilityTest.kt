package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.gamesbyian.chunks.ChunksSemantics
import io.github.gamesbyian.chunks.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ACCESSIBILITY category (docs/TEST-ARCHITECTURE.md). Enduring capabilities must be
 * reachable by stable identifier and expose a non-empty accessible name and a
 * 48dp-class target at 320dp and 200% text. This deliberately does **not** pin the
 * wording: renaming "+ Add" must not break it, while removing the name must.
 * No torrent is submitted and no remote is contacted.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi", fontScale = 2.0f)
class EnduringCapabilityAccessibilityTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun SemanticsNodeInteraction.accessibleName(): String {
        val node = fetchSemanticsNode()
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        return listOfNotNull(text, description).joinToString(" ").trim()
    }

    private fun SemanticsNodeInteraction.assertNamedAndTouchSized() {
        assertTrue("capability has no accessible name", accessibleName().isNotBlank())
        val bounds = getUnclippedBoundsInRoot()
        assertTrue("target shorter than 48dp: ${bounds.bottom - bounds.top}",
            bounds.bottom - bounds.top >= 48.dp)
    }

    @Test fun footerRootsAreNamedTouchSizedAndShareOneRow() {
        rule.waitForIdle()
        val tags = listOf(ChunksSemantics.FOOTER_TRANSFERS, ChunksSemantics.FOOTER_ADD,
            ChunksSemantics.FOOTER_CONNECTIONS, ChunksSemantics.FOOTER_OPTIONS)
        val tops = tags.map { tag ->
            val node = rule.onNodeWithTag(tag)
            node.assertIsDisplayed().assertHasClickAction().assertNamedAndTouchSized()
            node.getUnclippedBoundsInRoot().top
        }
        assertEquals("Footer roots must stay in a single row", 1, tops.distinct().size)
    }

    @Test fun addCapabilitiesAreNamedReachableAndTheDeviceTargetStartsSelected() {
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.ADD_SOURCE_FIELD).performScrollTo().assertIsDisplayed()
            .performTextInput("magnet:?xt=urn:btih:" + "ab".repeat(20))
        rule.waitForIdle()
        // Selected state is a Compose semantic, never inferred from copy.
        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_DEVICE).performScrollTo()
            .assertNamedAndTouchSized()
        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_DEVICE).assertIsSelected()
        rule.onNodeWithTag(ChunksSemantics.ADD_START_PREFERENCE).performScrollTo()
            .assertNamedAndTouchSized()
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).performScrollTo().assertNamedAndTouchSized()
        // With no authenticated server there is exactly no remote target offered.
        assertEquals(0, rule.onAllNodesWithTag(ChunksSemantics.ADD_TARGET_REMOTE)
            .fetchSemanticsNodes().size)
    }

    @Test fun connectionCreationIsNamedReachableAndTouchSized() {
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_CONNECTIONS).performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(ChunksSemantics.CONNECTION_ADD))
        rule.onNodeWithTag(ChunksSemantics.CONNECTION_ADD)
            .assertHasClickAction().assertNamedAndTouchSized()
        rule.onNodeWithTag(ChunksSemantics.CONNECTION_ADD).performClick()
        rule.onNodeWithTag(ChunksSemantics.CONNECTION_NAME).performScrollTo()
            .assertIsDisplayed().assertNamedAndTouchSized()
    }
}
