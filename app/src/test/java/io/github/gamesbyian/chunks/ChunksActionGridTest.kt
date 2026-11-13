package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

/** Two across by default, three for short actions, one column when 200% text needs it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class ChunksActionGridTest {
    @get:Rule val rule = createComposeRule()

    @Test fun columnCountFollowsWidthAndPreference() {
        assertEquals(2, actionGridColumns(380.dp, 136.dp, 8.dp, 2))
        assertEquals(2, actionGridColumns(380.dp, 136.dp, 8.dp, 3))
        assertEquals(3, actionGridColumns(380.dp, 100.dp, 8.dp, 3))
        assertEquals(1, actionGridColumns(380.dp, 272.dp, 8.dp, 2))
        assertEquals(1, actionGridColumns(100.dp, 136.dp, 8.dp, 2))
    }

    private fun action(n: Int): @Composable (Modifier) -> Unit = { cell ->
        OutlinedButton(onClick = {}, modifier = cell.testTag("a$n")) {
            Text(if (n == 2) "A much longer second action label" else "Action $n") }
    }
    private fun actions(): List<@Composable (Modifier) -> Unit> = listOf(action(1), action(2), action(3))

    private fun show(fontScale: Float) = rule.setContent {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
            ChunksTheme { ChunksActionGrid(actions(), Modifier.fillMaxWidth()) }
        }
    }

    @Test fun ordinaryTextPairsActionsWithEqualHeightsInOrder() {
        show(1f)
        val one = rule.onNodeWithTag("a1").assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        val two = rule.onNodeWithTag("a2").getUnclippedBoundsInRoot()
        val three = rule.onNodeWithTag("a3").getUnclippedBoundsInRoot()
        assertEquals("first row shares a top", one.top, two.top)
        assertEquals("cells in a row share height", one.bottom, two.bottom)
        assertTrue("reading order left to right", one.left < two.left)
        assertTrue("third wraps below", three.top >= one.bottom)
        assertEquals("third keeps a half-width cell", one.right - one.left, three.right - three.left)
    }

    @Test fun twoHundredPercentTextStacksSoNothingIsClipped() {
        show(2f)
        val one = rule.onNodeWithTag("a1").getUnclippedBoundsInRoot()
        val two = rule.onNodeWithTag("a2").getUnclippedBoundsInRoot()
        assertTrue("one column at 200%", two.top >= one.bottom)
        assertEquals(one.left, two.left)
    }
}
