package io.github.gamesbyian.chunks

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Geometry for the canonical transfer-card artwork column. No provider or engine side effects. */
class HeightLedTransferArtworkTest {
    @Test fun naturalPosterAndSquareArtHaveDifferentTextColumnOffsets() {
        val poster = heightLedArtworkBounds(108.dp, 108.dp, 2f / 3f)
        val square = heightLedArtworkBounds(108.dp, 108.dp, 1f)
        val magazine = heightLedArtworkBounds(108.dp, 108.dp, 3f / 4f)
        assertEquals(72.dp, poster.first)
        assertEquals(108.dp, poster.second)
        assertEquals(108.dp, square.first)
        assertEquals(108.dp, square.second)
        assertEquals(81.dp, magazine.first)
        assertTrue(poster.first < magazine.first && magazine.first < square.first)
    }

    @Test fun landscapeAndPanoramaAreReducedInBothDimensionsWhenWidthCapped() {
        val landscape = heightLedArtworkBounds(108.dp, 92.dp, 16f / 9f)
        val panorama = heightLedArtworkBounds(108.dp, 92.dp, 5f)
        assertEquals(92.dp, landscape.first)
        assertEquals(92.dp, panorama.first)
        assertEquals(92f * 9f / 16f, landscape.second.value, 0.001f)
        assertEquals(92f / 5f, panorama.second.value, 0.001f)
    }

    @Test fun invalidRatioFallsBackToSquareWithoutSpillingUnderText() {
        assertEquals(96.dp to 96.dp, heightLedArtworkBounds(108.dp, 96.dp, Float.NaN))
        assertEquals(96.dp to 96.dp, heightLedArtworkBounds(108.dp, 96.dp, 0f))
        assertEquals(96.dp to 96.dp, heightLedArtworkBounds(108.dp, 96.dp, Float.POSITIVE_INFINITY))
    }

    @Test fun narrowViewportCapLeavesWidthForText() {
        // Underlying card accounts for a 164dp minimum right column plus the 8dp gap.
        val constrainedMax = minOf(300.dp * 0.32f, 112.dp, (300.dp - 164.dp))
        val square = heightLedArtworkBounds(108.dp, constrainedMax, 1f)
        assertTrue(square.first + 8.dp + 164.dp <= 300.dp)
    }
}
