package io.github.gamesbyian.chunks

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual Android SEND/ClipData contracts, without starting any Add, remote
 * request, content-provider read, or payload transfer.
 */
@RunWith(AndroidJUnit4::class)
class ExternalClipMagnetUriInstrumentedTest {
    private val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40) +
        "&tr=https%3A%2F%2Fexample.org%2Fannounce%3Ftoken%3Dprivatefixture"
    private val other = "magnet:?xt=urn:btih:" + "b".repeat(40)

    private fun shared(uri: Uri, text: String? = null): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = ClipDescription.MIMETYPE_TEXT_URILIST
            clipData = ClipData.newRawUri("shared magnet", uri)
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
        }

    @Test fun oneClipDataMagnetUriIsAReviewDraftNotADocument() {
        val handoff = shared(Uri.parse(magnet))
        assertEquals(ExternalAddSource.Magnet(magnet),
            ExternalAddSource.fromIntent(handoff))
        assertEquals(ExternalAddSource.Magnet(magnet),
            ExternalAddSource.fromIntent(shared(Uri.parse(magnet), magnet)))
        // Only the scheme of a mixed-case URI gets normalized. Preserve exact
        // private tracker/query values instead of matching the hash alone.
        val mixed = "MAGNET:?" + magnet.substring("magnet:?".length)
        assertEquals(ExternalAddSource.Magnet(magnet),
            ExternalAddSource.fromIntent(shared(Uri.parse(mixed))))
        assertEquals(ExternalAddSource.Magnet(magnet),
            ExternalAddSource.fromIntent(shared(Uri.parse(mixed), magnet)))

        // The source was only recognized; an incomplete magnet still requires
        // the ordinary visible Add validator to reject it.
        assertEquals(ExternalAddSource.Magnet("magnet:?xt=urn:btih:bad"),
            ExternalAddSource.fromIntent(shared(Uri.parse("MAGNET:?xt=urn:btih:bad"))))
    }

    @Test fun contradictionsMultipleItemsAndDocumentUrisStayFailClosed() {
        assertNull(ExternalAddSource.fromIntent(shared(Uri.parse(magnet), other)))
        assertNull(ExternalAddSource.fromIntent(shared(Uri.parse(magnet), "a description")))

        val conflictingRepresentations = Intent(Intent.ACTION_SEND).apply {
            type = ClipDescription.MIMETYPE_TEXT_URILIST
            clipData = ClipData("conflict", arrayOf(ClipDescription.MIMETYPE_TEXT_URILIST),
                ClipData.Item(other, null, Uri.parse(magnet)))
        }
        assertNull(ExternalAddSource.fromIntent(conflictingRepresentations))

        val multiple = shared(Uri.parse(magnet)).apply {
            clipData?.addItem(ClipData.Item(Uri.parse(other)))
        }
        assertNull(ExternalAddSource.fromIntent(multiple))

        val multipleTexts = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            clipData = ClipData.newPlainText("first", magnet).apply {
                addItem(ClipData.Item(other))
            }
            putExtra(Intent.EXTRA_TEXT, magnet)
        }
        assertNull(ExternalAddSource.fromIntent(multipleTexts))

        val documentUri = Uri.parse("content://provider.example/file.torrent")
        assertNull(ExternalAddSource.fromIntent(shared(documentUri, magnet)))
        assertNull(ExternalAddSource.fromIntent(shared(Uri.parse(
            "https://example.org/file.torrent"), magnet)))
        assertNull(ExternalAddSource.fromIntent(shared(Uri.parse(magnet), magnet).apply {
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://provider.example/file.torrent"))
        }))
        // A valid real content document still wins over incidental magnet text.
        val validDocument = Intent(Intent.ACTION_SEND).apply {
            type = "application/x-bittorrent"
            putExtra(Intent.EXTRA_STREAM, documentUri)
            clipData = ClipData.newRawUri("document", documentUri)
            putExtra(Intent.EXTRA_TEXT, magnet)
        }
        assertEquals(ExternalAddSource.TorrentDocument(documentUri),
            ExternalAddSource.fromIntent(validDocument))
        // Ordinary single text SEND remains unchanged.
        assertEquals(ExternalAddSource.Magnet(magnet),
            ExternalAddSource.fromIntent(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, magnet)
            }))
    }
}
