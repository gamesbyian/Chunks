package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
class AuthenticatedTransfersFooterTest {
    @get:Rule val rule = createComposeRule()

    @Test fun bottomControlsPreserveExactExistingCallbacksAndTouchTargets() {
        var transfers = 0
        var adds = 0
        var options = 0
        rule.setContent {
            ChunksTheme {
                Box(Modifier.width(412.dp)) {
                    AuthenticatedTransfersFooter(
                        optionsOpen = false,
                        onTransfers = { transfers++ },
                        onAdd = { adds++ },
                        onOptions = { options++ }
                    )
                }
            }
        }
        val top = rule.onNodeWithText("Transfers").getUnclippedBoundsInRoot()
        val add = rule.onNodeWithText("+ Add").getUnclippedBoundsInRoot()
        val opts = rule.onNodeWithContentDescription("Options").getUnclippedBoundsInRoot()
        for (rect in listOf(top, add, opts)) {
            assertTrue("footer touch target must remain 48dp class", rect.bottom - rect.top >= 48.dp)
        }
        assertTrue(top.right < add.left && add.right < opts.left)
        rule.onNodeWithText("Transfers").performClick()
        rule.onNodeWithText("+ Add").performClick()
        rule.onNodeWithContentDescription("Options").performClick()
        assertEquals(1, transfers)
        assertEquals(1, adds)
        assertEquals(1, options)
    }

    @Test fun atPrimaryPhoneWidthEnlargedTextDoesNotCompressThreeNavigationButtons() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChunksTheme {
                    Box(Modifier.width(412.dp)) {
                        AuthenticatedTransfersFooter(false, {}, {}, {})
                    }
                }
            }
        }
        val t = rule.onNodeWithText("Transfers").getUnclippedBoundsInRoot()
        val a = rule.onNodeWithText("+ Add").getUnclippedBoundsInRoot()
        val o = rule.onNodeWithContentDescription("Options").getUnclippedBoundsInRoot()
        assertTrue("Two primary actions remain on one readable row", t.right < a.left)
        assertTrue("Advanced navigation receives its own full-width row", o.top >= a.bottom)
        assertTrue("Options should cover most of the footer at 200% text", o.right - o.left >= 280.dp)
    }

    @Test fun phoneFooterActionsNameTheExactScopeWithoutChangingRemoteSemantics() {
        var transfers = 0
        var adds = 0
        var options = 0
        rule.setContent {
            ChunksTheme {
                Box(Modifier.width(412.dp)) {
                    AuthenticatedTransfersFooter(false,
                        onTransfers = { transfers++ },
                        onAdd = { adds++ },
                        onOptions = { options++ },
                        environmentLabel = "this phone")
                }
            }
        }
        rule.onNodeWithContentDescription("Show transfers on this phone").performClick()
        rule.onNodeWithContentDescription("Add torrent on this phone").performClick()
        rule.onNodeWithContentDescription("Options on this phone").performClick()
        assertEquals(1, transfers)
        assertEquals(1, adds)
        assertEquals(1, options)
    }

    @Test
    @Config(qualifiers = "w320dp-h700dp-mdpi")
    fun enlargedTypeFooterRetainsAllThreeActions() {
        var adds = 0
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChunksTheme {
                    Box(Modifier.width(320.dp)) {
                        AuthenticatedTransfersFooter(false, {}, { adds++ }, {})
                    }
                }
            }
        }
        val transfers = rule.onNodeWithText("Transfers")
        val add = rule.onNodeWithText("+ Add")
        val options = rule.onNodeWithContentDescription("Options")
        val firstRow = transfers.getUnclippedBoundsInRoot()
        val addRow = add.getUnclippedBoundsInRoot()
        val secondRow = options.getUnclippedBoundsInRoot()
        assertTrue("Transfers and Add share the readable first row", firstRow.right < addRow.left)
        assertTrue("Options moves below at 200% text", secondRow.top >= addRow.bottom)
        assertTrue("Options remains a full-size target", secondRow.bottom - secondRow.top >= 48.dp)
        add.performClick()
        options.performClick()
        assertEquals(1, adds)
    }
}
