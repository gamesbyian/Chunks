package io.github.gamesbyian.chunks

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Presentation-only at narrow 200% font scale: the controls open disclosures
 * but cannot grant a batch lease, submit Add, or mutate any transfer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi", fontScale = 2.0f)
class TransfersInlineToolbarTest {
    @get:Rule val rule = createComposeRule()

    @Test fun searchAndTwoAccessibleToolsRemainReachableInOneRow() {
        val query = mutableStateOf("")
        val filters = mutableStateOf(false)
        val batch = mutableStateOf(false)
        var filterClicks = 0
        var batchClicks = 0
        rule.setContent {
            MaterialTheme {
                TransfersInlineToolbar(
                    search = query.value,
                    onSearchChange = { query.value = it },
                    filtersExpanded = filters.value,
                    batchExpanded = batch.value,
                    filtersActive = false,
                    onFilters = { filters.value = !filters.value; filterClicks++ },
                    onBatch = { batch.value = !batch.value; batchClicks++ }
                )
            }
        }
        val filter = rule.onNodeWithTag(ChunksSemantics.TRANSFERS_FILTER_SORT)
        val tools = rule.onNodeWithTag(ChunksSemantics.TRANSFERS_BATCH_TOOLS)
        filter.assertIsDisplayed()
        tools.assertIsDisplayed()
        for (node in listOf(filter, tools)) {
            val size = node.getUnclippedBoundsInRoot()
            assertTrue("toolbar icon must retain 48dp height at 200% type",
                size.bottom - size.top >= 48.dp)
        }
        rule.onNodeWithContentDescription("Filters and sort").performClick()
        rule.onNodeWithContentDescription("Batch tools").performClick()
        rule.runOnIdle {
            assertTrue(filters.value)
            assertTrue(batch.value)
            assertEquals(1, filterClicks)
            assertEquals(1, batchClicks)
        }
        assertEquals("Expanded",
            filter.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        assertEquals("Expanded",
            tools.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        rule.onNodeWithText("Search names or hashes").performTextReplacement("Episode")
        rule.runOnIdle {
            assertEquals("Episode", query.value)
            assertEquals(1, filterClicks)
            assertEquals(1, batchClicks)
        }
        filter.assertExists()
        tools.assertExists()
    }
}
