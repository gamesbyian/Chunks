package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        assertNotNull(context.getDrawable(R.drawable.ic_chunks_foreground))
        assertNotNull(context.getDrawable(R.drawable.ic_chunks_monochrome))
    }
}
