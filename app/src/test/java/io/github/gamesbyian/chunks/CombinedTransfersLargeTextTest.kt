package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.model.UnifiedTransferQuery
import io.github.gamesbyian.chunks.model.UnifiedSortKey
import io.github.gamesbyian.chunks.model.UnifiedTransferFilter
import io.github.gamesbyian.chunks.model.EnvironmentHue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Synthetic UI acceptance only: no rTorrent, native service or physical handset. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class CombinedTransfersLargeTextTest {
    @get:Rule val rule = createComposeRule()

    private fun show(openPhone: IntArray, back: IntArray) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        CombinedViewPreferences(context).save(UnifiedTransferQuery())
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChunksTheme {
                    Surface(color = ChunksColors.Background) {
                        CombinedTransfersScreen(
                            server = null, savedProfiles = emptyList(),
                            onOpenPhone = { openPhone[0]++ },
                            onOpenServer = null,
                            onBack = { back[0]++ })
                    }
                }
            }
        }
    }

    @Test fun narrow200PercentControlsRemainScrollReachableWithNoEngineStarted() {
        val opened = intArrayOf(0)
        val backed = intArrayOf(0)
        show(opened, backed)
        rule.onNodeWithText("All environments").assertExists()
        rule.onNodeWithText("Filter transfers").performScrollTo()
        // This label lives inside a lazy list item, not a nested ScrollState.
        // Scroll the LazyColumn itself to the matching descendant.
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Sort by"))
        rule.onNodeWithText("Sort by").assertExists()

        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Select…"))
        val select = rule.onNodeWithText("Select…")
        val selectionBounds = select.getUnclippedBoundsInRoot()
        assertTrue("Selection button is at least 48dp tall", selectionBounds.bottom - selectionBounds.top >= 48.dp)
        select.performClick()
        rule.onNodeWithText("Done selecting").assertExists()
        rule.onNodeWithText("Done selecting").performClick()

        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Open On this phone"))
        val open = rule.onNodeWithText("Open On this phone")
        val openBounds = open.getUnclippedBoundsInRoot()
        assertTrue("Phone navigation keeps a 48dp touch target", openBounds.bottom - openBounds.top >= 48.dp)
        open.performClick()
        assertEquals(1, opened[0])
        assertEquals(0, backed[0])
        assertNull("Read-only combined view must never start the native engine", LocalEngineHolder.current())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
    fun at200PercentGroupToggleAndEnvironmentColoursRemainReachable() {
        val opened = intArrayOf(0)
        val backed = intArrayOf(0)
        show(opened, backed)
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Group by environment"))
        val grouping = rule.onNodeWithContentDescription("Group by environment")
        val bounds = grouping.getUnclippedBoundsInRoot()
        assertTrue("Group toggle retains its minimum touch target", bounds.bottom - bounds.top >= 48.dp)

        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Environment colours…"))
        val colours = rule.onNodeWithText("Environment colours…")
        val colourBounds = colours.getUnclippedBoundsInRoot()
        assertTrue("Colour chooser remains scroll-reachable", colourBounds.bottom - colourBounds.top >= 48.dp)
        colours.performClick()
        rule.onNodeWithText("Done with colours").assertExists()
        rule.onNodeWithText("Done with colours").performClick()
        assertEquals(0, opened[0])
        assertEquals(0, backed[0])
        assertNull(LocalEngineHolder.current())
    }
    @Test fun at320And200PercentFilterAndLastSortOptionNeedNoHorizontalGesture() {
        val opened = intArrayOf(0)
        val backed = intArrayOf(0)
        show(opened, backed)
        val list = rule.onNode(hasScrollToIndexAction())
        // All chips must be inside the vertical list. Nothing requires a
        // sideways-only gesture or a horizontal-scroll semantics action.
        list.performScrollToNode(hasText("Attention"))
        rule.onNodeWithText("Attention").performClick()
        assertEquals(UnifiedTransferFilter.ATTENTION,
            CombinedViewPreferences(ApplicationProvider.getApplicationContext<android.content.Context>()).load().filter)
        list.performScrollToNode(hasText("Peers"))
        rule.onNodeWithText("Peers").performClick()
        assertEquals(UnifiedSortKey.PEERS,
            CombinedViewPreferences(ApplicationProvider.getApplicationContext<android.content.Context>()).load().sortKey)
        assertEquals(0, opened[0])
        assertEquals(0, backed[0])
        assertNull(LocalEngineHolder.current())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
    fun at412And200PercentLastPaletteChoiceIsVerticallyReachableAnd48dp() {
        val opened = intArrayOf(0)
        val backed = intArrayOf(0)
        show(opened, backed)
        val list = rule.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasText("Environment colours…"))
        rule.onNodeWithText("Environment colours…").performClick()
        val lastColour = "On this phone colour " + EnvironmentHue.entries.last().label
        list.performScrollToNode(hasContentDescription(lastColour))
        val swatch = rule.onNodeWithContentDescription(lastColour)
        val bounds = swatch.getUnclippedBoundsInRoot()
        assertTrue("Every palette option retains a 48dp target",
            bounds.bottom - bounds.top >= 48.dp)
        swatch.performClick()
        assertEquals(0, opened[0])
        assertEquals(0, backed[0])
        assertNull(LocalEngineHolder.current())
    }

}
