package io.github.gamesbyian.chunks

import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed Android package-manager contract, rather than asserting only
 * Intent parsing. The OS must actually expose Chunks as a handler, while
 * unrelated binary files must not be commandeered by an overbroad filter.
 *
 * This does not claim physical Files/Chrome/browser provider acceptance.
 */
@RunWith(AndroidJUnit4::class)
class TorrentIntentResolutionInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun chunksCanHandle(intent: Intent): Boolean {
        intent.addCategory(Intent.CATEGORY_DEFAULT)
        intent.setPackage(context.packageName)
        @Suppress("DEPRECATION")
        val matches = context.packageManager.queryIntentActivities(
            intent, PackageManager.MATCH_DEFAULT_ONLY)
        return matches.any {
            it.activityInfo.packageName == context.packageName &&
                it.activityInfo.name == MainActivity::class.java.name
        }
    }

    @Test fun recognizedTorrentMimeContentUrisResolveToChunksFromAndroidOpenWith() {
        val document = Uri.parse("content://fixture.documents/download/1")
        for (type in listOf(
            "application/x-bittorrent",
            "application/x-torrent",
            "application/vnd.bittorrent"
        )) {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(document, type)
            assertTrue("Android did not register Chunks as a VIEW handler for $type",
                chunksCanHandle(view))
            val send = Intent(Intent.ACTION_SEND).setType(type)
            assertTrue("Android did not register Chunks as a SEND handler for $type",
                chunksCanHandle(send))
        }
    }

    @Test fun magnetLinksResolveAsBrowsableViewWithoutAnAppSpecificChooser() {
        val magnet = Uri.parse("magnet:?xt=urn:btih:" + "a".repeat(40))
        val view = Intent(Intent.ACTION_VIEW, magnet)
            .addCategory(Intent.CATEGORY_BROWSABLE)
        assertTrue("Android did not register Chunks for browsable magnet links",
            chunksCanHandle(view))
    }

    @Test fun unrelatedMimeAndOrdinaryHttpsUrlsAreNotClaimed() {
        val document = Uri.parse("content://fixture.documents/download/2")
        for (type in listOf("application/pdf", "image/png", "text/calendar")) {
            assertFalse("Chunks unexpectedly claims unrelated $type content",
                chunksCanHandle(Intent(Intent.ACTION_VIEW).setDataAndType(document, type)))
        }
        assertFalse("Chunks must not hijack normal HTTPS navigation",
            chunksCanHandle(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://example.org/test.torrent"))))
    }
}
