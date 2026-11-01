package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.TransferFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class RemoteTransferFilterChoicesTest {
    @get:Rule val rule = createComposeRule()

    private fun show(width: Int, chosen: MutableList<TransferFilter>) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                var selected by androidx.compose.runtime.remember {
                    mutableStateOf(TransferFilter.ALL)
                }
                ChunksTheme {
                    Surface {
                        Box(Modifier.width(width.dp)) {
                            RemoteTransferFilterChoices(selected, onChoose = {
                                chosen += it
                                selected = it
                            })
                        }
                    }
                }
            }
        }
    }

    @Test fun allSevenFiltersAreReachableAndSelectableAt320DpWith200PercentFont() {
        val chosen = mutableListOf<TransferFilter>()
        show(320, chosen)
        for (filter in TransferFilter.entries) {
            val node = rule.onNodeWithText(filter.label)
            node.assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("Filter must fit in the 320dp column: ${filter.label}",
                bounds.left >= 0.dp && bounds.right <= 320.dp)
        }
        rule.onNodeWithText("Needs sorting").performClick().assertIsSelected()
        assertEquals(listOf(TransferFilter.NEEDS_SORTING), chosen)
        rule.onNodeWithText("All").performClick().assertIsSelected()
        assertEquals(listOf(TransferFilter.NEEDS_SORTING, TransferFilter.ALL), chosen)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
    fun longLabelsWrapAndKeepFullClickTargetsAt412Dp() {
        val chosen = mutableListOf<TransferFilter>()
        show(412, chosen)
        rule.onNodeWithText("Traffic now").assertIsDisplayed().performClick().assertIsSelected()
        rule.onNodeWithText("0 B/s down").assertIsDisplayed().performClick().assertIsSelected()
        assertEquals(listOf(TransferFilter.TRANSFERRING, TransferFilter.ZERO_DOWNLOAD), chosen)
    }
}
