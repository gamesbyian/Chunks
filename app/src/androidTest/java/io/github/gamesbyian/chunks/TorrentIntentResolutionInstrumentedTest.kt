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
 * Intent parsing. The OS must actually expose Chunks for opaque generic
 * binary provider IDs; this necessarily also offers the app for unrelated
 * generic files. Byte-level review rejects those before any torrent Add.
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

    @Test fun uppercaseMagnetViewSchemeResolvesAndPreservesQueryForAddReview() {
        // IntentFilter scheme matching on Android is case-sensitive even
        // though URI schemes are semantically case-insensitive. Test the
        // installed manifest, not just the source parser.
        val mixed = "MAGNET:?XT=URN:BTIH:" + "A".repeat(40) +
            "&tr=https%3A%2F%2Fexample.org%2Fannounce%3Ftoken%3Dtest"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(mixed))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        assertTrue("Android did not register Chunks for uppercase magnet VIEW",
            chunksCanHandle(intent))
        val normalized = ExternalMagnetInput.fromIntent(intent)
        assertTrue("Expected only lower-case URI scheme normalization",
            normalized == "magnet:?" + mixed.substring("MAGNET:?".length))
        assertTrue("The resolved source must retain normal explicit Add review",
            ExternalAddSource.fromIntent(intent) == ExternalAddSource.Magnet(normalized!!))
    }

    @Test fun genericBinaryOpaqueDocumentsResolveButRemainUntrustedAddCandidates() {
        val named = Uri.parse("content://fixture.documents/download/sample.torrent")
        val opaque = Uri.parse("content://fixture.documents/document/93482")
        val unrelated = Uri.parse("content://fixture.documents/download/sample.zip")
        // Android's chooser cannot inspect DISPLAY_NAME. To offer Chunks for
        // opaque torrent document IDs, it must also offer unrelated binary
        // files that happen to use the same generic MIME declaration.
        for (document in listOf(named, opaque, unrelated)) {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(document, "application/octet-stream")
            assertTrue("Android must offer Chunks for generic content URIs",
                chunksCanHandle(view))
            assertTrue("This only selects a source, not permission to Add",
                SharedTorrentDocument.fromIntent(view) == document)
        }
        assertTrue("Single binary SEND must be available for opaque provider IDs",
            chunksCanHandle(Intent(Intent.ACTION_SEND)
                .setType("application/octet-stream")))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, opaque)
        }
        assertTrue(ExternalAddSource.fromIntent(send) ==
            ExternalAddSource.TorrentDocument(opaque))
        // An unrelated declared MIME still must not be claimed, and no raw
        // file path can be elevated to a content URI by its extension alone.
        assertFalse(chunksCanHandle(Intent(Intent.ACTION_VIEW)
            .setDataAndType(opaque, "application/pdf")))
        assertTrue(SharedTorrentDocument.fromIntent(
            Intent(Intent.ACTION_VIEW).setDataAndType(
                Uri.parse("file:///sdcard/Download/sample.torrent"),
                "application/octet-stream")) == null)
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
