package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
class RemoteAdvancedListActionsTest {
    @get:Rule val rule = createComposeRule()

    private fun show(
        options: Boolean, selecting: Boolean, tools: Boolean = false,
        enabled: Boolean = true, scale: Float = 1f,
        onSelect: () -> Unit = {}, onTools: () -> Unit = {}
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                ChunksTheme {
                    Box(Modifier.width(376.dp)) {
                        RemoteAdvancedListActions(
                            optionsOpen = options,
                            selecting = selecting,
                            toolsOpen = tools,
                            enabled = enabled,
                            onToggleSelection = onSelect,
                            onToggleTools = onTools
                        )
                    }
                }
            }
        }
    }

    @Test fun normalTransferListHasNoPermanentBatchOrSpecialistToolRow() {
        show(options = false, selecting = false)
        rule.onNodeWithText("Select transfers for a batch").assertDoesNotExist()
        rule.onNodeWithText("More tools").assertDoesNotExist()
    }

    @Test fun footerOptionsRevealsBothActionsWithoutIssuingCommands() {
        var selectClicks = 0
        var toolClicks = 0
        show(options = true, selecting = false,
            onSelect = { selectClicks++ }, onTools = { toolClicks++ })
        val select = rule.onNodeWithText("Select transfers for a batch")
        val tools = rule.onNodeWithText("More tools")
        assertTrue("Options keeps both touch targets large",
            select.getUnclippedBoundsInRoot().bottom -
                select.getUnclippedBoundsInRoot().top >= 48.dp)
        select.performClick()
        tools.performClick()
        assertEquals(1, selectClicks)
        assertEquals(1, toolClicks)
    }

    @Test
    @Config(qualifiers = "w320dp-h700dp-mdpi")
    fun activeBatchAlwaysOffersExitAfterOptionsClosesEvenWith200PercentText() {
        var exits = 0
        show(options = false, selecting = true, scale = 2f,
            onSelect = { exits++ })
        val exit = rule.onNodeWithText("Exit batch selection")
        rule.onNodeWithText("More tools").assertDoesNotExist()
        assertTrue(exit.getUnclippedBoundsInRoot().bottom -
            exit.getUnclippedBoundsInRoot().top >= 48.dp)
        exit.performClick()
        assertEquals(1, exits)
    }

    @Test fun readOnlyBusyStateNeverPermitsAnAccidentalBatchEntry() {
        show(options = true, selecting = false, enabled = false)
        rule.onNodeWithText("Select transfers for a batch").assertIsNotEnabled()
        // Read-only More tools remains available to explain the current state.
        rule.onNodeWithText("More tools").performClick()
    }
}
