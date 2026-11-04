package io.github.gamesbyian.chunks.visual

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.ChunkedProgressBar
import io.github.gamesbyian.chunks.ChunksColors
import io.github.gamesbyian.chunks.ChunksTheme
import io.github.gamesbyian.chunks.ChunksTone
import io.github.gamesbyian.chunks.progressFill
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Non-text contrast (WCAG 1.4.11, 3:1) for transfer progress: the whole
 * track length must be visible on every surface a row sits on, and every
 * state's completed fill must stand out from the empty trough. Colour stays
 * supplementary: rows still carry percentage and status text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class ProgressTrackContrastTest {
    @get:Rule val rule = createComposeRule()

    private fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + .05f) / (lo + .05f)
    }

    @Test fun trackEdgeAndEveryStateFillMeetNonTextContrast() {
        for ((name, surface) in listOf("Background" to ChunksColors.Background,
                "Surface" to ChunksColors.Surface, "SurfaceRaised" to ChunksColors.SurfaceRaised,
                "Muted" to ChunksColors.Muted)) {
            val ratio = contrast(ChunksColors.ProgressEdge, surface)
            assertTrue("track edge on $name is $ratio:1", ratio >= 3f)
        }
        for (tone in ChunksTone.entries) {
            val ratio = contrast(tone.progressFill, ChunksColors.Trough)
            assertTrue("$tone fill on trough is $ratio:1", ratio >= 3f)
        }
    }

    @Test fun renderedEmptyBarShowsItsFullLengthOnACard() {
        rule.setContent {
            ChunksTheme {
                Box(Modifier.background(ChunksColors.Surface).padding(8.dp)) {
                    ChunkedProgressBar(0f, Modifier.width(300.dp).testTag("bar"),
                        tone = ChunksTone.INACTIVE)
                }
            }
        }
        val pixels = rule.onNodeWithTag("bar").captureToImage().toPixelMap()
        val midRow = pixels.height / 2
        // The far-right edge column marks where 100% would end.
        val rightEdge = (pixels.width - 1 downTo pixels.width - 4)
            .map { pixels[it, midRow] }
            .maxBy { it.luminance() }
        val ratio = contrast(rightEdge, ChunksColors.Surface)
        assertTrue("rendered right end of an empty bar is $ratio:1 on its card", ratio >= 3f)
    }
}
