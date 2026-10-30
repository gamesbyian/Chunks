package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A real coordinate click on the underlying full-width Details action must
 * remain possible when the floating return-to-top action is visible.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class ReturnToTopOverlayLargeTextTest {
    @get:Rule val rule = createComposeRule()

    private fun show(width: Int, scale: Float, clicks: MutableList<String>) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                ChunksTheme {
                    Surface {
                        Box(Modifier.width(width.dp).height(200.dp)) {
                            OutlinedButton(
                                onClick = { clicks += "details" },
                                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                                    .heightIn(min = 48.dp)
                            ) { Text("Details") }
                            ReturnToTopControl(visible = true,
                                onClick = { clicks += "top" },
                                modifier = Modifier.align(Alignment.BottomEnd)
                                    .padding(end = 16.dp))
                        }
                    }
                }
            }
        }
    }

    @Test fun tinyPhoneAt200PercentDoesNotCoverTheUnderlyingDetailsClick() {
        val clicks = mutableListOf<String>()
        show(width = 320, scale = 2f, clicks)
        val float = rule.onNodeWithContentDescription("Back to top").getUnclippedBoundsInRoot()
        assertTrue("Floating arrow remains a >=48dp target", float.bottom - float.top >= 48.dp)
        assertTrue("Floating arrow must leave screen centre unobscured", float.left > 160.dp)
        assertTrue("Large text needs a compact floating footprint", float.right - float.left <= 100.dp)
        rule.onNodeWithText("Details").performClick()
        assertEquals("Underlay tap must not jump to top", listOf("details"), clicks)
        rule.onNodeWithContentDescription("Back to top").performClick()
        assertEquals(listOf("details", "top"), clicks)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
    fun biggerPhoneAlsoCompactsAt200Percent() {
        val clicks = mutableListOf<String>()
        show(width = 412, scale = 2f, clicks)
        val float = rule.onNodeWithContentDescription("Back to top").getUnclippedBoundsInRoot()
        assertTrue(float.right - float.left <= 100.dp)
        assertTrue(float.left > 206.dp)
        rule.onNodeWithText("Details").performClick()
        assertEquals(listOf("details"), clicks)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w600dp-h700dp-mdpi")
    fun wideDefaultTextKeepsNamedVisibleButton() {
        val clicks = mutableListOf<String>()
        show(width = 560, scale = 1f, clicks)
        rule.onNodeWithText("Back to top").assertExists()
        rule.onNodeWithContentDescription("Back to top").performClick()
        assertEquals(listOf("top"), clicks)
    }
}
