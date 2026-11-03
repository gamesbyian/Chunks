package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The normal phone Transfers route must begin with inventory, not a brand hero. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
class ChunksBrandHeaderTest {
    @get:Rule val rule = createComposeRule()

    @Test fun normalDailyHeaderShowsWordmarkWithoutPermanentTagline() {
        rule.setContent {
            ChunksTheme {
                Box(Modifier.width(412.dp)) { ChunksBrandHeader() }
            }
        }
        rule.onNodeWithText("Chunks").assertIsDisplayed()
        rule.onNodeWithText("Your downloads have places to be.").assertDoesNotExist()
        val bounds = rule.onNodeWithText("Chunks").getUnclippedBoundsInRoot()
        assertTrue("The title must remain compact instead of occupying a hero", (bounds.bottom - bounds.top) < 64.dp)
    }

    @Test
    @Config(qualifiers = "w320dp-h700dp-mdpi")
    fun doubleSystemTextStillAvoidsBrandHeroOnPhone() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChunksTheme {
                    Box(Modifier.width(320.dp)) { ChunksBrandHeader() }
                }
            }
        }
        rule.onNodeWithText("Chunks").assertIsDisplayed()
        rule.onNodeWithText("Your downloads have places to be.").assertDoesNotExist()
        val bounds = rule.onNodeWithText("Chunks").getUnclippedBoundsInRoot()
        assertTrue("Double text should not recreate the illustrated header", (bounds.bottom - bounds.top) < 108.dp)
    }
}
