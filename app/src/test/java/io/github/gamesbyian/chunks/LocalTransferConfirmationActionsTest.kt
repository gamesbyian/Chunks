package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class LocalTransferConfirmationActionsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun phoneRemoveAt200PercentPresentsDistinctFullWidthTargetsWithoutCommands() {
        var removes = 0
        var declined = 0
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChunksTheme { Surface {
                    Box(Modifier.width(304.dp)) {
                        LocalTransferConfirmationActions(
                            "Remove, keep files", "Keep",
                            enabled = true,
                            onConfirm = { removes++ }, onCancel = { declined++ })
                    }
                } }
            }
        }
        val confirm = rule.onNodeWithText("Remove, keep files").getUnclippedBoundsInRoot()
        val cancel = rule.onNodeWithText("Keep").getUnclippedBoundsInRoot()
        assertTrue("Confirm and cancel stack at 200%", cancel.top >= confirm.bottom)
        assertTrue("Both have >=48dp semantic touch targets",
            rule.onNode(androidx.compose.ui.test.hasText("Remove, keep files") and
                androidx.compose.ui.test.hasClickAction()).getUnclippedBoundsInRoot().height >= 48.dp)
        rule.onNodeWithText("Keep").performClick()
        assertEquals(0, removes)
        assertEquals(1, declined)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
    fun phoneToServerReviewAlsoStacksAt200Percent() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChunksTheme { Surface {
                    Box(Modifier.width(396.dp)) {
                        LocalTransferConfirmationActions("Review server Add", "Cancel",
                            onConfirm = {}, onCancel = {})
                    }
                } }
            }
        }
        val confirm = rule.onNodeWithText("Review server Add").getUnclippedBoundsInRoot()
        val cancel = rule.onNodeWithText("Cancel").getUnclippedBoundsInRoot()
        assertTrue(cancel.top >= confirm.bottom)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w600dp-h700dp-mdpi")
    fun wideDefaultTextRetainsCompactAdjacentControls() {
        rule.setContent {
            ChunksTheme { Surface {
                Box(Modifier.width(560.dp)) {
                    LocalTransferConfirmationActions("Remove, keep files", "Keep",
                        onConfirm = {}, onCancel = {})
                }
            } }
        }
        val confirm = rule.onNodeWithText("Remove, keep files").getUnclippedBoundsInRoot()
        val cancel = rule.onNodeWithText("Keep").getUnclippedBoundsInRoot()
        assertEquals(confirm.top, cancel.top)
        assertTrue(confirm.right < cancel.left)
    }
}
