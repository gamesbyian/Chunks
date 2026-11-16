package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xhdpi")
class OwnerUxBatchGeometryTest {
    @get:Rule val rule = createComposeRule()
    private fun render(scale: Float) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                ChunksTheme {
                    var active by remember { mutableStateOf(FooterDestination.TRANSFERS) }
                    Column(Modifier.fillMaxWidth()) {
                        ChunksBrandHeader()
                        TransfersInlineToolbar("", {}, false, false, false, {}, {})
                        AuthenticatedTransfersFooter(null, onTransfers = { active = FooterDestination.TRANSFERS },
                            onAdd = { active = FooterDestination.ADD }, onConnections = { active = FooterDestination.CONNECTIONS },
                            onOptions = { active = FooterDestination.OPTIONS }, activeDestination = active)
                    }
                }
            }
        }
    }
    private fun verify(scale: Float, image: String) {
        render(scale)
        rule.onNodeWithTag(ChunksSemantics.TRANSFERS_FILTER_SORT)
            .assertWidthIsEqualTo(48.dp).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(ChunksSemantics.TRANSFERS_BATCH_TOOLS)
            .assertWidthIsEqualTo(48.dp).assertHeightIsAtLeast(48.dp)
        for (tag in listOf(ChunksSemantics.FOOTER_TRANSFERS, ChunksSemantics.FOOTER_ADD,
            ChunksSemantics.FOOTER_CONNECTIONS, ChunksSemantics.FOOTER_OPTIONS)) {
            val node = rule.onNodeWithTag(tag)
            val before = node.getUnclippedBoundsInRoot()
            node.assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
            assertEquals("selection must not resize navigation", before, node.getUnclippedBoundsInRoot())
        }
        rule.onRoot().captureRoboImage("build/chunks-visual/$image.png")
    }
    @Test fun pixelWidthControlsShareStableSelectionBounds() = verify(1f, "owner-batch-controls-412")
    @Test @Config(qualifiers = "w320dp-h700dp-xhdpi")
    fun narrowControlsRemainAccessibleAt200Percent() = verify(2f, "owner-batch-controls-320-200pct")
}
