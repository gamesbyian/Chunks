package io.github.gamesbyian.chunks

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.model.EnvironmentHue
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.UnifiedTransferQuery
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native rendering of production Combined/details with synthetic, read-only transfer facts. */
@RunWith(AndroidJUnit4::class)
class CombinedDetailIdentityInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun narrowDetailsKeepExactIdentityColourAndLongPlaceAt200Percent() = verify(320, 700)
    @Test fun regularDetailsKeepExactIdentityColourAndLongPlaceAt200Percent() = verify(412, 892)

    private fun verify(width: Int, height: Int) {
        CombinedViewPreferences(rule.activity).save(UnifiedTransferQuery())
        val scope = "https://detail-scope.example/rutorrent2/RPC2\u0000reader"
        val id = requireNotNull(EnvironmentSnapshots.serverIdOfAliasScope(scope))
        val hue = EnvironmentHue.ORANGE
        EnvironmentColourStore(rule.activity).choose(id, hue)
        val path = "/home/reader/downloads/a-very-long-unbroken-folder-name-with-numbered-instance-two/complete"
        val row = RemoteTransfer("c".repeat(40), "A very long transfer title with a clear exact environment", 100, 50,
            false, path, null, 0)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density * 0.75f, 2f)) {
                ChunksTheme { Box(Modifier.requiredSize(width.dp, height.dp).testTag("combined-detail-viewport")) { Surface {
                    CombinedTransfersScreen(CombinedServerSource(scope, listOf(row), false, true,
                        System.currentTimeMillis()), emptyList(), onOpenPhone = {},
                        onOpenServer = {}, onBack = {})
                } } }
            }
        }
        // At this density both real dp viewports fit inside the emulator's inset content window.
        val viewport = rule.onNodeWithTag("combined-detail-viewport")
        viewport.assertWidthIsEqualTo(width.dp).assertHeightIsEqualTo(height.dp)
        val viewportNode = viewport.fetchSemanticsNode()
        // Use unclipped geometry: clipped bounds alone could conceal overflow.
        val origin = viewportNode.positionInRoot
        val size = viewportNode.size
        val host = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("The complete viewport must fit inside the Activity content window",
            origin.x >= host.left && origin.y >= host.top &&
                origin.x + size.width <= host.right && origin.y + size.height <= host.bottom)
        val capturedViewport = viewport.captureToImage()
        assertEquals(size.width, capturedViewport.width)
        assertEquals(size.height, capturedViewport.height)
        // The canonical compact row opens the exact full Details on its first tap.
        val target = "Details for ${row.name} on ${serverEnvironmentLabel(scope)}"
        rule.waitUntil(10_000) {
            runCatching {
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(target))
            }.isSuccess
        }
        rule.onNodeWithContentDescription(target).performClick()
        val title = rule.onNodeWithText(row.name).performScrollTo()
        val titleLayouts = mutableListOf<TextLayoutResult>()
        title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        assertTrue(titleLayouts.isNotEmpty())
        assertFalse("Long title must remain fully readable in details", titleLayouts.single().hasVisualOverflow)
        rule.onNodeWithText("Environment: ${serverEnvironmentLabel(scope)}").performScrollTo().assertExists()
        // Palette preferences are read off-main; require the actual rendered result.
        fun colourPixels(): Int {
            val pixels = rule.onNodeWithTag("combined-detail-viewport").captureToImage().toPixelMap()
            var count = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                if (pixels[x, y].toArgb() == hue.argb.toInt()) count++
            }
            return count
        }
        runCatching { rule.waitUntil(5_000) { colourPixels() >= 32 } }
        val matching = colourPixels()
        assertTrue("The chosen environment colour must survive opening details", matching >= 32)
        save("combined-detail-identity-$width-200pct", rule.onNodeWithTag("combined-detail-viewport").captureToImage().asAndroidBitmap())
        val place = rule.onNodeWithText("Save folder reported by engine: $path").performScrollTo()
        place.assertExists()
        val layouts = mutableListOf<TextLayoutResult>()
        place.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertFalse("Long save folder must wrap without losing text", layouts.single().hasVisualOverflow)
        rule.onNodeWithText("Download rate: not reported").performScrollTo().assertExists()
        rule.onNodeWithText("Upload rate: 0 B/s").performScrollTo().assertExists()
        save("combined-detail-place-$width-200pct", rule.onNodeWithTag("combined-detail-viewport").captureToImage().asAndroidBitmap())
        rule.onNodeWithText("Back to transfers").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
    }

    private fun save(name: String, image: Bitmap) {
        val resolver = rule.activity.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ChunksAddAtlas")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
        resolver.openOutputStream(uri).use { check(image.compress(Bitmap.CompressFormat.PNG, 100, requireNotNull(it))) }
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
    }
}
