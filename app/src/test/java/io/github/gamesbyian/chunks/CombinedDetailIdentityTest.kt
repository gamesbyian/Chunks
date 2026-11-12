package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.model.EnvironmentHue
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.UnifiedTransferQuery
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
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
class CombinedDetailIdentityTest {
    @get:Rule val rule = createComposeRule()

    @Test fun exactEnvironmentKeepsItsChosenColourWhenOpeningDetailsAtLargeType() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        CombinedViewPreferences(context).save(UnifiedTransferQuery())
        val scope = "https://detail-scope.example/rutorrent2/RPC2\u0000reader"
        val id = requireNotNull(EnvironmentSnapshots.serverIdOfAliasScope(scope))
        val hue = EnvironmentHue.ORANGE
        EnvironmentColourStore(context).choose(id, hue)
        val row = RemoteTransfer("c".repeat(40), "A transfer with its own environment identity", 100, 50,
            false, "/downloads", 0, 0)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ChunksTheme { Surface {
                    CombinedTransfersScreen(CombinedServerSource(scope, listOf(row), false, true,
                        System.currentTimeMillis()), emptyList(), onOpenLocal = {},
                        onOpenServer = {}, onBack = {})
                } }
            }
        }
        val target = "Details for ${row.name} on ${serverEnvironmentLabel(scope)}"
        rule.waitUntil(10_000) {
            runCatching {
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(target))
            }.isSuccess
        }
        rule.onNodeWithContentDescription(target).performClick()
        rule.onNodeWithText("Environment: ${serverEnvironmentLabel(scope)}").performScrollTo().assertExists()
        // Palette preferences are read off-main; require the actual rendered result.
        fun colourPixels(): Int {
            val pixels = rule.onRoot().captureToImage().toPixelMap()
            var count = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                if (pixels[x, y].toArgb() == hue.argb.toInt()) count++
            }
            return count
        }
        runCatching { rule.waitUntil(5_000) { colourPixels() >= 32 } }
        val matching = colourPixels()
        assertTrue("The exact source's chosen colour must remain visible in details; found $matching pixels",
            matching >= 32)
        rule.onRoot().captureRoboImage("build/chunks-visual/combined-detail-identity-320-200pct.png")
    }
}
