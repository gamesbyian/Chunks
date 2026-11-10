package io.github.gamesbyian.chunks

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Intent extras and parcel classes; no engine, provider or Add. */
@RunWith(AndroidJUnit4::class)
class PendingExternalAddSourceInstrumentedTest {
    private val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40) +
        "&tr=https%3A%2F%2Ftracker.example%2FprivateFixture"

    @Test fun invalidNewShareCannotClearQueuedMagnetOrTakeItsPrivateQuery() {
        val accepted = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, magnet)
        }
        val queued = PendingExternalAddSource(null, null)
            .withIncoming(ExternalAddSource.fromIntent(accepted))
        assertEquals(magnet, queued.text)
        assertNull(queued.torrentDocument)
        org.junit.Assert.assertFalse("Value diagnostics must never leak a private tracker",
            queued.toString().contains("privateFixture"))

        // The sender provides both an invalid document extra and an unrelated
        // text source. Never substitute that text, or erase the prior source.
        val invalid = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, "not a Uri")
            putExtra(Intent.EXTRA_TEXT, "magnet:?xt=urn:btih:" + "b".repeat(40))
        }
        assertNull(ExternalAddSource.fromIntent(invalid))
        assertEquals(queued, queued.withIncoming(ExternalAddSource.fromIntent(invalid)))
        assertEquals(queued, queued.withIncoming(ExternalAddSource.fromIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/unrelated")))))
    }

    @Test fun invalidNewShareCannotClearQueuedDocumentAndValidReplacementRemainsExplicit() {
        val uri = Uri.parse("content://documents.example/provider/fixture.torrent")
        val queued = PendingExternalAddSource(null, uri)
        val rejected = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/x-bittorrent"
        }
        assertEquals(queued, queued.withIncoming(ExternalAddSource.fromIntent(rejected)))

        // An actually recognized newer Share can still enter the existing
        // destination review. Occupied-draft Keep/Replace remains a separate UI.
        val changed = queued.withIncoming(ExternalAddSource.Magnet(magnet))
        assertEquals(magnet, changed.text)
        assertNull(changed.torrentDocument)
        val restored = changed.withIncoming(ExternalAddSource.TorrentDocument(uri))
        assertEquals(uri, restored.torrentDocument)
        assertNull(restored.text)
    }

    @Test fun malformedFollowUpCannotEraseSignedHttpsTorrentLink() {
        val signedLink = "HTTPS://downloads.example.org/fixture.torrent?token=privateFixture"
        val queued = PendingExternalAddSource(null, null)
            .withIncoming(ExternalAddSource.TorrentLink(signedLink))
        assertEquals(signedLink, queued.text)
        val malformed = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, 17) // unsupported parcel extra type
            putExtra(Intent.EXTRA_TEXT, "https://downloads.example.org/other.torrent")
        }
        assertNull(ExternalAddSource.fromIntent(malformed))
        assertEquals(queued, queued.withIncoming(ExternalAddSource.fromIntent(malformed)))
        org.junit.Assert.assertFalse("Signed link secrets must not appear in implicit diagnostics",
            queued.toString().contains("privateFixture"))
    }
}
