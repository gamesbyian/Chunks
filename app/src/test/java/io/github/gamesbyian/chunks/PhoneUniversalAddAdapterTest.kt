package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Pure adapter contract, not a fabricated engine/peer/piece acceptance claim. */
class PhoneUniversalAddAdapterTest {
    private val infohash = "a".repeat(40)
    private val root = File("/tmp/chunks-phone-add-verified")
    private val place = LocalDownloadPlace(LocalPlaceKind.APP_STORAGE, root, "Phone", "Test fixture")
    private val otherPlace = LocalDownloadPlace(LocalPlaceKind.PUBLIC_DOWNLOADS, root, "Other", "Test fixture")

    private fun magnet() = requireNotNull(
        MagnetDraftCheck.inspect("magnet:?xt=urn:btih:$infohash&tr=https%3A%2F%2Ftracker.example%2Fpasskey")
            as? MagnetDraftCheck.Valid)

    private fun intent(start: Boolean = false, child: String = "episode") =
        PhoneUniversalAddAdapter.magnetIntent(magnet(), place, child, start, allowArtwork = true)

    private fun prepared(start: Boolean = false, child: String = "episode"):
        Pair<PhoneAddAttemptSession, UniversalAddCoordinator.Ticket> {
        val session = PhoneAddAttemptSession()
        assertTrue(PhoneUniversalAddAdapter.review(session.coordinator, intent(start, child),
            inventoryConfirmed = true, inventoryStale = false).accepted)
        assertTrue(session.coordinator.authorizeByUser())
        return session to requireNotNull(session.coordinator.beginSubmission(10_000L))
    }

    private fun status(stopped: Boolean = true, uploadOnly: Boolean = false,
                       metadataKnown: Boolean = true, nativeToken: String = "fresh",
                       hash: String = infohash, savePath: String = File(root, "episode").path) =
        LocalTransferStatus(
            infoHash = hash,
            name = "test",
            state = if (metadataKnown) LocalTransferState.SEEDING else LocalTransferState.DOWNLOADING_METADATA,
            stopped = stopped,
            progressPpm = 0,
            wantedBytes = 1_024,
            doneBytes = 0,
            payloadDownloadedBytes = 0,
            payloadUploadedBytes = 0,
            peers = 0,
            savePath = savePath,
            error = null,
            metadataKnown = metadataKnown,
            uploadOnly = uploadOnly,
            nativeReviewToken = nativeToken
        )

    @Test fun validMetainfoAndNormalizedMagnetFreezeExactPlaceStartAndPrivateSource() {
        val bytes = TorrentLinkFixtures.singleFileV1("show.mkv", 1_024)
        val review = PendingLocalAdd(TorrentMetainfoReader.preview(bytes), bytes)
        val torrent = PhoneUniversalAddAdapter.torrentIntent(review, place, "",
            startWhenAdded = true, allowArtwork = false)
        assertArrayEquals(bytes, torrent.source.metainfoForSubmission())
        bytes.fill(0)
        assertFalse("Frozen metainfo must be copied", torrent.source.metainfoForSubmission()!!.all { it == 0.toByte() })
        assertTrue(torrent.startWhenAdded)
        assertFalse(torrent.allowArtwork)
        val magnet = PhoneUniversalAddAdapter.magnetIntent(magnet(), place, "episode",
            startWhenAdded = false, allowArtwork = true)
        assertEquals(infohash, magnet.source.expectedInfoHash)
        assertFalse(magnet.startWhenAdded)
        assertFalse(magnet.toString().contains("passkey"))
    }

    @Test fun changedReviewedTorrentBytesCannotBeAuthorizedUsingAnOldVisibleHash() {
        // Equal-sized names keep both bencoded documents the same length.
        val original = TorrentLinkFixtures.singleFileV1("alpha.mkv", 1_024)
        val replacement = TorrentLinkFixtures.singleFileV1("bravo.mkv", 1_024)
        assertEquals(original.size, replacement.size)
        val reviewed = PendingLocalAdd(TorrentMetainfoReader.preview(original), original)
        // A mutable review holder may change before a queued coroutine starts.
        // The shared ticket must not borrow the earlier preview's authority.
        replacement.copyInto(original)
        try {
            PhoneUniversalAddAdapter.torrentIntent(reviewed, place, "",
                startWhenAdded = false, allowArtwork = false)
            fail("Changed metainfo must not create a shared Add ticket")
        } catch (_: IllegalArgumentException) {
            // Rejection occurs before any native engine call.
        }
    }

    @Test fun liveInventoryAndCurrentPlaceRequiredBeforeAnyAttempt() {
        val session = PhoneAddAttemptSession()
        assertFalse(PhoneUniversalAddAdapter.review(session.coordinator, intent(),
            inventoryConfirmed = false, inventoryStale = false).accepted)
        assertFalse(session.coordinator.authorizeByUser())
        assertNull(session.coordinator.beginSubmission(10_000L))
        assertFalse(PhoneUniversalAddAdapter.review(session.coordinator, intent(),
            inventoryConfirmed = true, inventoryStale = true).accepted)
        assertTrue(PhoneUniversalAddAdapter.review(session.coordinator, intent(),
            inventoryConfirmed = true, inventoryStale = false).accepted)
        assertTrue(session.coordinator.authorizeByUser())
        val ticket = requireNotNull(session.coordinator.beginSubmission(10_000L))
        assertTrue(PhoneUniversalAddAdapter.matchesPlace(ticket, place, "episode"))
        assertFalse(PhoneUniversalAddAdapter.matchesPlace(ticket, place, "different"))
        assertFalse(PhoneUniversalAddAdapter.matchesPlace(ticket, otherPlace, "episode"))
        assertEquals(File(root, "episode").canonicalFile,
            PhoneUniversalAddAdapter.expectedDirectory(ticket, place)?.canonicalFile)
        assertNull(PhoneUniversalAddAdapter.expectedDirectory(ticket, otherPlace))
        assertNull(session.coordinator.beginSubmission(10_001L))
    }

    @Test fun exactNativeGenerationAndReviewedPlaceProveStoppedNewAdd() {
        val (session, ticket) = prepared()
        session.noteNativeResult(ticket, LocalAddResult.AddedStopped(status()), place)
        assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, session.coordinator.phase)
        assertEquals(UniversalAddCoordinator.Readback.VERIFIED,
            session.reconcile(ticket, status(), place, 11_000L))
        assertEquals(UniversalAddCoordinator.Phase.VERIFIED, session.coordinator.phase)
        assertNull(session.coordinator.beginSubmission(12_000L))
        assertTrue(session.finishReviewedAttempt())
        assertNull(session.attemptedTicket)
    }

    @Test fun mismatchedGenerationIsExactSessionOnlyAndNeverClaimsNewRegistration() {
        val (session, ticket) = prepared()
        session.noteNativeResult(ticket, LocalAddResult.AddedStopped(status(nativeToken = "one")), place)
        assertEquals(UniversalAddCoordinator.Readback.EXACT_SESSION_CONFIRMED,
            session.reconcile(ticket, status(nativeToken = "two"), place, 12_000L))
        assertEquals(UniversalAddCoordinator.Phase.SESSION_CONFIRMED, session.coordinator.phase)
        assertTrue(session.finishReviewedAttempt())
        assertNull(session.attemptedTicket)
    }

    @Test fun nativeRefusalAfterPossibleRegistrationCannotEraseWriteFence() {
        val (session, ticket) = prepared()
        session.noteNativeResult(ticket, LocalAddResult.Refused("Could not read back native handle"), place)
        assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, session.coordinator.phase)
        assertNull(session.coordinator.beginSubmission(10_001L))
        assertEquals(UniversalAddCoordinator.Readback.NOT_OBSERVED,
            session.reconcile(ticket, null, place, 11_000L))
        assertFalse(session.finishReviewedAttempt())
        assertEquals(UniversalAddCoordinator.Readback.EXACT_SESSION_CONFIRMED,
            session.reconcile(ticket, status(nativeToken = "new-but-unattributed"), place, 12_000L))
        assertTrue(session.finishReviewedAttempt())
    }

    @Test fun staleReadAndWrongDestinationCannotGrantNewAdd() {
        val (session, ticket) = prepared()
        val native = status(nativeToken = "n1")
        session.noteNativeResult(ticket, LocalAddResult.AddedStopped(native), place)
        assertEquals(UniversalAddCoordinator.Readback.NEEDS_FRESH_INVENTORY,
            session.reconcile(ticket, native, place, 9_999L))
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            session.reconcile(ticket, native.copy(savePath = "/tmp/other-folder"), place, 12_000L))
        assertEquals(UniversalAddCoordinator.Phase.OBSERVED_UNCERTAIN, session.coordinator.phase)
        assertFalse(session.finishReviewedAttempt())
    }

    @Test fun metadataOnlyUploadModeIsNotMistakenForStartedPayload() {
        val (session, ticket) = prepared(start = false)
        val metadata = status(stopped = false, uploadOnly = true, metadataKnown = false)
        session.noteNativeResult(ticket, LocalAddResult.FetchingMetadata(metadata), place)
        assertEquals(UniversalAddCoordinator.Readback.VERIFIED,
            session.reconcile(ticket, metadata, place, 11_000L))

        val (startSession, startTicket) = prepared(start = true)
        startSession.noteNativeResult(startTicket, LocalAddResult.FetchingMetadata(metadata), place)
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            startSession.reconcile(startTicket, metadata, place, 11_000L))
        assertEquals(UniversalAddCoordinator.Readback.VERIFIED,
            startSession.reconcile(startTicket,
                metadata.copy(stopped = false, uploadOnly = false, metadataKnown = true), place, 12_000L))
    }

    @Test fun positivePrewriteRefusalReopensReviewWithoutAuthorizingAutomaticRetry() {
        val (session, ticket) = prepared()
        assertTrue(session.coordinator.refusedBeforeAnyWrite(ticket))
        assertEquals(UniversalAddCoordinator.Phase.REVIEWED, session.coordinator.phase)
        assertNull(session.coordinator.beginSubmission(11_000L))
        assertTrue(session.coordinator.authorizeByUser())
        assertNotNull(session.coordinator.beginSubmission(12_000L))
    }
}
