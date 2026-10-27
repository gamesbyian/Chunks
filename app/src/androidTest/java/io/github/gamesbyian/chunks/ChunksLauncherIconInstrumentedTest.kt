package io.github.gamesbyian.chunks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.VectorDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The launcher foreground must render from the SVG-derived native vector, never a PNG. */
@RunWith(AndroidJUnit4::class)
class ChunksLauncherIconInstrumentedTest {
    @Test fun installedApplicationExposesNativeAdaptiveVectorIcon() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(R.mipmap.ic_launcher, context.applicationInfo.icon)
        val launcher = context.packageManager.getApplicationIcon(context.packageName)
        assertNotNull(launcher)
        assertTrue("Android 29+ must expose adaptive launcher icon", launcher is AdaptiveIconDrawable)
        val foreground = context.getDrawable(R.drawable.chunks_launcher_artwork)
        assertNotNull(foreground)
        assertTrue("Runtime launcher foreground must be a VectorDrawable", foreground is VectorDrawable)
        val pixels = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        foreground!!.setBounds(0, 0, pixels.width, pixels.height)
        foreground.draw(Canvas(pixels))
        var visible = 0
        for (y in 0 until pixels.height step 4) {
            for (x in 0 until pixels.width step 4) {
                if ((pixels.getPixel(x, y) ushr 24) != 0) visible++
            }
        }
        assertTrue("SVG-derived vector must actually render visible artwork", visible > 500)
        assertEquals("No black square bezel in transparent vector foreground", 0, pixels.getPixel(0, 0) ushr 24)
    }
}
