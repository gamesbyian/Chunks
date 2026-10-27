package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import android.graphics.BitmapFactory
import android.graphics.drawable.AdaptiveIconDrawable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Installed-app branding proof; screenshot and launcher home-screen review remain separate. */
@RunWith(AndroidJUnit4::class)
class ChunksLauncherIconInstrumentedTest {
    @Test fun installedApplicationExposesNativeAdaptiveIcon() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(R.mipmap.ic_launcher, context.applicationInfo.icon)
        assertNotNull(context.packageManager.getApplicationIcon(context.packageName))
        val launcher = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue("Android 29+ must expose adaptive launcher icon", launcher is AdaptiveIconDrawable)
        assertNotNull(context.getDrawable(R.drawable.chunks_launcher_artwork))
        val options = BitmapFactory.Options().apply { inScaled = false }
        val source = BitmapFactory.decodeResource(
            context.resources, R.drawable.chunks_launcher_artwork, options
        )
        assertNotNull("New owner-approved launcher art must decode", source)
        assertTrue("Source icon needs high-resolution artwork", source.width >= 512 && source.height >= 512)
        assertTrue("Icon should be square", source.width == source.height)
    }
}
