package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The status label is functional information. At 200% font scale and narrow
 * widths it must wrap, rather than clipping itself to a single unreadable line.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class StatusPillLargeTextTest {
    @get:Rule val rule = createComposeRule()

    @Test fun longStatusWrapsFullyAtTwoHundredPercentFontScale() {
        val label = "Downloading metadata and verifying selected files"
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                ChunksTheme {
                    Box(Modifier.width(160.dp)) {
                        StatusPill(label, ChunksTone.ACTIVE)
                    }
                }
            }
        }

        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue("Compose must expose measurable text layout", it(layouts))
        }
        val layout = layouts.single()
        assertTrue("200% text must be permitted to wrap", layout.lineCount > 1)
        assertFalse("No visible status text may be clipped", layout.hasVisualOverflow)
    }
}
