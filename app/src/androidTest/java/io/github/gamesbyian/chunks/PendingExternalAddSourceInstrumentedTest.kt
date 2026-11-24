package io.github.gamesbyian.chunks

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.create.SharedCreateDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
        val owner = LocalAddAttemptOwner()
        owner.offerAdd(ExternalAddSource.fromIntent(accepted))
        val queued = owner.incomingDelivery as ExternalIntakeDelivery.Add
        assertEquals(ExternalAddSource.Magnet(magnet), queued.source)
        assertFalse("Value diagnostics must never leak a private tracker", queued.toString().contains("privateFixture"))

        // The sender provides both an invalid document extra and an unrelated
        // text source. Never substitute that text, or erase the prior source.
        val invalid = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, "not a Uri")
            putExtra(Intent.EXTRA_TEXT, "magnet:?xt=urn:btih:" + "b".repeat(40))
        }
        assertNull(ExternalAddSource.fromIntent(invalid))
        owner.offerAdd(ExternalAddSource.fromIntent(invalid))
        owner.offerAdd(ExternalAddSource.fromIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/unrelated"))))
        assertSame(queued, owner.incomingDelivery)
    }

    @Test fun invalidNewShareCannotClearQueuedDocumentAndValidReplacementRemainsExplicit() {
        val uri = Uri.parse("content://documents.example/provider/fixture.torrent")
        val owner = LocalAddAttemptOwner()
        owner.offerAdd(ExternalAddSource.TorrentDocument(uri))
        val queued = owner.incomingDelivery
        val rejected = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/x-bittorrent"
        }
        owner.offerAdd(ExternalAddSource.fromIntent(rejected))
        assertSame(queued, owner.incomingDelivery)

        // An actually recognized newer Share can still enter the existing
        // destination review. Occupied-draft Keep/Replace remains a separate UI.
        owner.offerAdd(ExternalAddSource.Magnet(magnet))
        assertFalse(owner.claimIncoming(queued))
        assertEquals(ExternalAddSource.Magnet(magnet), (owner.incomingDelivery as ExternalIntakeDelivery.Add).source)
        owner.offerAdd(ExternalAddSource.TorrentDocument(uri))
        assertEquals(ExternalAddSource.TorrentDocument(uri), (owner.incomingDelivery as ExternalIntakeDelivery.Add).source)
    }

    @Test fun malformedFollowUpCannotEraseSignedHttpsTorrentLink() {
        val signedLink = "HTTPS://downloads.example.org/fixture.torrent?token=privateFixture"
        val owner = LocalAddAttemptOwner()
        owner.offerAdd(ExternalAddSource.TorrentLink(signedLink))
        val queued = owner.incomingDelivery as ExternalIntakeDelivery.Add
        assertEquals(ExternalAddSource.TorrentLink(signedLink), queued.source)
        val malformed = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, 17) // unsupported parcel extra type
            putExtra(Intent.EXTRA_TEXT, "https://downloads.example.org/other.torrent")
        }
        assertNull(ExternalAddSource.fromIntent(malformed))
        owner.offerAdd(ExternalAddSource.fromIntent(malformed))
        assertSame(queued, owner.incomingDelivery)
        assertFalse("Signed link secrets must not appear in implicit diagnostics",
            queued.toString().contains("privateFixture"))
    }

    @Test fun supersededUnclaimedDocumentCannotResurfaceFromASecondQueue() {
        val owner = LocalAddAttemptOwner()
        val first = Uri.parse("content://documents.example/provider/first.torrent")
        val next = Uri.parse("content://documents.example/provider/next.torrent")
        owner.readingSharedDocument.value = first
        owner.offerAdd(ExternalAddSource.TorrentDocument(next))
        val stale = owner.incomingDelivery
        assertEquals(ExternalAddSource.TorrentDocument(next),
            (stale as ExternalIntakeDelivery.Add).source)
        assertNull("Unclaimed Add has no second pending authority", owner.pendingSharedDocument.value)
        owner.offerCreate(SharedCreateDocument.Request.File(next))
        val create = owner.incomingDelivery
        assertFalse(owner.claimIncoming(stale))
        assertTrue(owner.claimIncoming(create))
        assertNull(owner.incomingDelivery)
        assertNull(owner.pendingSharedDocument.value)
        assertEquals(first, owner.readingSharedDocument.value)
        assertNull(owner.session.attemptedTicket)
    }

    @Test fun olderConsumerCannotClaimAnIdenticalNewDeliveryOrACreateShare() {
        val owner = LocalAddAttemptOwner()
        val uri = Uri.parse("content://documents.example/provider/fixture.torrent")
        val document = ExternalAddSource.TorrentDocument(uri)
        owner.offerAdd(document)
        val first = owner.incomingDelivery
        owner.offerAdd(document)
        val second = owner.incomingDelivery
        assertFalse(owner.claimIncoming(first))
        assertSame(second, owner.incomingDelivery)
        assertTrue(owner.claimIncoming(second))
        assertFalse(owner.claimIncoming(second))
        assertNull(owner.incomingDelivery)

        owner.offerAdd(document)
        val superseded = owner.incomingDelivery
        owner.offerCreate(SharedCreateDocument.Request.File(uri))
        assertFalse(owner.claimIncoming(superseded))
        assertEquals(SharedCreateDocument.Request.File(uri), (owner.incomingDelivery as ExternalIntakeDelivery.Create).request)
        assertFalse(owner.hasDocumentReview())
    }
}
