package io.github.gamesbyian.chunks

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native installed-app Compose acceptance with the *real Android system*
 * font-scale setting applied before this Activity is launched, not a synthetic
 * CompositionLocalProvider or merely doubling individual text pixel sizes.
 *
 * This is a diagnostic, source-controlled smoke gate for actual app navigation
 * and five surviving pixel screenshots, not a running TalkBack audit or final
 * font-art approval. It does not mutate torrents or require remote credentials.
 */
@RunWith(AndroidJUnit4::class)
class SystemFontScaleComposeInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val tabs = listOf("Transfers", "Places", "Organize", "Server")

    private fun savePixels(name: String) {
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        assertTrue("Real Compose screenshot too small", image.width >= 300 && image.height >= 500)
        val pixels = IntArray(10)
        for (index in pixels.indices) {
            val x = ((index+1)*image.width / (pixels.size+1)).coerceAtMost(image.width-1)
            val y = ((index+1)*image.height / (pixels.size+1)).coerceAtMost(image.height-1)
            pixels[index] = image.getPixel(x,y)
        }
        assertTrue("Screenshot contained no opaque Android pixels",pixels.any {
            android.graphics.Color.alpha(it) == 255
        })
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "chunks-compose-200pct-$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/ChunksCompose200Pct")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values
        )) { "MediaStore rejected real Compose 200% screenshot $name" }
        resolver.openOutputStream(uri).use { raw ->
            val stream = requireNotNull(raw) { "No MediaStore output stream" }
            assertTrue("Failed to encode real Compose screen $name",
                image.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        assertEquals("Published real Compose screenshot",1,
            resolver.update(uri,published,null,null))
    }

    @Test
    fun actualChunksActivityKeepsItsCoreScreensAccessibleAtRealSystemFontScale200Percent() {
        val actual = rule.activity.resources.configuration.fontScale
        assertTrue("Android app must run with real system font scale at least 190%, got $actual",
            actual >= 1.9f)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("chunks. Your downloads have places to be.")
            .assertExists()
        savePixels("transfers-initial")
        // Existing installed-app navigation paths, no fake Composables and
        // no network actions. Visit every main navigation destination and
        // return to Transfers, retaining actual pixels for review.
        for (tab in listOf("Places", "Organize", "Server", "Transfers")) {
            rule.onNodeWithText(tab).assertExists().performScrollTo().performClick()
            rule.waitForIdle()
            savePixels(if (tab == "Transfers") "transfers-return" else tab.lowercase())
        }
        // Five distinct real app captures: initial and return-to-Transfers,
        // plus Places, Organize and Server, with no duplicate filenames.
    }
}
