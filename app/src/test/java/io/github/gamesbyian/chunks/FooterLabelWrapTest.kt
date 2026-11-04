package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Footer labels at the smallest width and 200% text are never broken mid-word. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-xxhdpi", fontScale = 2.0f)
class FooterLabelWrapTest {
    @get:Rule val rule = createComposeRule()

    private fun lines(label: String): Int {
        val node = rule.onNode(hasText(label), useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single().lineCount
    }

    @Test fun transfersAddAndOptionsEachFitOnOneLine() {
        rule.setContent {
            ChunksTheme { Surface {
                AuthenticatedTransfersFooter(optionsOpen = false, onTransfers = {}, onAdd = {},
                    onOptions = {}, environmentLabel = "this phone", imeVisible = false)
            } }
        }
        assertEquals("Transfers", 1, lines("Transfers"))
        assertEquals("+ Add", 1, lines("+ Add"))
        assertEquals("Options", 1, lines("Options"))
        rule.onNodeWithContentDescription("Show transfers on this phone").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Add torrent on this phone").assertHeightIsAtLeast(48.dp)
    }
}
