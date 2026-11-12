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
class LocalUniversalAddAdapterTest {
    private val infohash = "a".repeat(40)
    private val root = File("/tmp/chunks-local-add-verified")
    private val place = LocalDownloadPlace(LocalPlaceKind.APP_STORAGE, root, "Local", "Test fixture")
    private val otherPlace = LocalDownloadPlace(LocalPlaceKind.PUBLIC_DOWNLOADS, root, "Other", "Test fixture")

    private fun magnet() = requireNotNull(
        MagnetDraftCheck.inspect("magnet:?xt=urn:btih:$infohash&tr=https%3A%2F%2Ftracker.example%2Fpasskey")
            as? MagnetDraftCheck.Valid)

    private fun intent(start: Boolean = false, child: String = "episode") =
        LocalUniversalAddAdapter.magnetIntent(magnet(), place, child, start, allowArtwork = true)

    private fun prepared(start: Boolean = false, child: String = "episode"):
        Pair<LocalAddAttemptSession, UniversalAddCoordinator.Ticket> {
        val session = LocalAddAttemptSession()
        assertTrue(LocalUniversalAddAdapter.review(session.coordinator, intent(start, child),
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
        val torrent = LocalUniversalAddAdapter.torrentIntent(review, place, "",
            startWhenAdded = true, allowArtwork = false)
        assertArrayEquals(bytes, torrent.source.metainfoForSubmission())
        bytes.fill(0)
        assertFalse("Frozen metainfo must be copied", torrent.source.metainfoForSubmission()!!.all { it == 0.toByte() })
        assertTrue(torrent.startWhenAdded)
        assertFalse(torrent.allowArtwork)
        val magnet = LocalUniversalAddAdapter.magnetIntent(magnet(), place, "episode",
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
            LocalUniversalAddAdapter.torrentIntent(reviewed, place, "",
                startWhenAdded = false, allowArtwork = false)
            fail("Changed metainfo must not create a shared Add ticket")
        } catch (_: IllegalArgumentException) {
            // Rejection occurs before any native engine call.
        }
    }

    @Test fun existingLocalHashIsRefusedReadOnlyOnlyWhenInventoryIsFresh() {
        val existing = status(hash = infohash)
        val different = status(hash = "b".repeat(40))
        assertFalse(LocalUniversalAddAdapter.alreadyPresentInFreshInventory(
            infohash, listOf(existing), fresh = false))
        assertFalse(LocalUniversalAddAdapter.alreadyPresentInFreshInventory(
            infohash, listOf(different), fresh = true))
        assertTrue(LocalUniversalAddAdapter.alreadyPresentInFreshInventory(
            infohash.uppercase(), listOf(existing, different), fresh = true))
        // No coordinator authorization, native call, target substitution or
        // instance mutation occurs from an already-existing hash check.
    }

    @Test fun changedTrackerUrlWithIdenticalInfohashAndPreviewCannotAcquireOldReviewApproval() {
        val source = TorrentLinkFixtures.singleFileV1("same.mkv", 1_024)
        val preview = TorrentMetainfoReader.preview(source)
        val review = PendingLocalAdd(preview, source)
        val oldUrl = "https://tracker.example/announce"
        val changedUrl = "https://tracker.example/bnnounce"
        val offset = source.toString(Charsets.ISO_8859_1).indexOf(oldUrl)
        assertTrue("Fixture must have an announce URL", offset >= 0)
        assertEquals(oldUrl.length, changedUrl.length)
        changedUrl.toByteArray(Charsets.US_ASCII).copyInto(source, offset)
        // v1 infohash covers the info dictionary, and preview redacts the
        // tracker URL's private path. Both remain identical after this edit.
        assertEquals(preview, TorrentMetainfoReader.preview(source))
        assertFalse(review.stillMatchesReviewedSource(source))
        try {
            LocalUniversalAddAdapter.torrentIntent(review, place, "",
                startWhenAdded = false, allowArtwork = true)
            fail("Altered complete metainfo must never use the older approval")
        } catch (_: IllegalArgumentException) {
            // Exact-source denial occurs before review authorization/native IO.
        }
    }

    @Test fun visibleAddEligibilityRequiresAnActuallyAcceptedFirstInventory() {
        // Compose's initial empty list has stale=false before its first native
        // observation. A tappable Add here would be rejected by the shared
        // coordinator while leaving the UI looking as if Add was submitted.
        assertFalse(LocalUniversalAddAdapter.inventoryReadyForReview(false, false))
        assertFalse(LocalUniversalAddAdapter.inventoryReadyForReview(false, true))
        assertFalse(LocalUniversalAddAdapter.inventoryReadyForReview(true, true))
        assertTrue(LocalUniversalAddAdapter.inventoryReadyForReview(true, false))
        val session = LocalAddAttemptSession()
        assertFalse(LocalUniversalAddAdapter.review(session.coordinator, intent(),
            inventoryConfirmed = false, inventoryStale = false).accepted)
        assertEquals(UniversalAddCoordinator.Phase.EMPTY, session.coordinator.phase)
        assertNull(session.attemptedTicket)
    }

    @Test fun liveInventoryAndCurrentPlaceRequiredBeforeAnyAttempt() {
        val session = LocalAddAttemptSession()
        assertFalse(LocalUniversalAddAdapter.review(session.coordinator, intent(),
            inventoryConfirmed = false, inventoryStale = false).accepted)
        assertFalse(session.coordinator.authorizeByUser())
        assertNull(session.coordinator.beginSubmission(10_000L))
        assertFalse(LocalUniversalAddAdapter.review(session.coordinator, intent(),
            inventoryConfirmed = true, inventoryStale = true).accepted)
        assertTrue(LocalUniversalAddAdapter.review(session.coordinator, intent(),
            inventoryConfirmed = true, inventoryStale = false).accepted)
        assertTrue(session.coordinator.authorizeByUser())
        val ticket = requireNotNull(session.coordinator.beginSubmission(10_000L))
        assertTrue(LocalUniversalAddAdapter.matchesPlace(ticket, place, "episode"))
        assertFalse(LocalUniversalAddAdapter.matchesPlace(ticket, place, "different"))
        assertFalse(LocalUniversalAddAdapter.matchesPlace(ticket, otherPlace, "episode"))
        assertEquals(File(root, "episode").canonicalFile,
            LocalUniversalAddAdapter.expectedDirectory(ticket, place)?.canonicalFile)
        assertNull(LocalUniversalAddAdapter.expectedDirectory(ticket, otherPlace))
        assertNull(session.coordinator.beginSubmission(10_001L))
    }

    @Test fun exactNativeGenerationAndReviewedPlaceProveStoppedNewAdd() {
        val (session, ticket) = prepared()
        session.noteNativeResult(ticket, LocalAddResult.AddedStopped(status()), place)
        assertTrue("A native generation receipt can announce a registered job",
            session.announceNativeRegistrationOnce(ticket))
        assertFalse("A single ticket cannot emit a second Combined notice",
            session.announceNativeRegistrationOnce(ticket))
        assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, session.coordinator.phase)
        assertEquals(UniversalAddCoordinator.Readback.VERIFIED,
            session.reconcile(ticket, status(), place, 11_000L))
        assertEquals(UniversalAddCoordinator.Phase.VERIFIED, session.coordinator.phase)
        assertFalse("Fresh full-intent proof cannot duplicate the registration notice",
            session.announceVerifiedRegistrationOnce(ticket))
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
        assertFalse("An uncertain post-native refusal cannot announce a new transfer",
            session.announceNativeRegistrationOnce(ticket))
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
        assertTrue(session.announceNativeRegistrationOnce(ticket))
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

    @Test fun peerlessMagnetWithStartRequestedAcknowledgesObservedJobWithoutStartingOrRetrying() {
        val (session, ticket) = prepared(start = true)
        session.bindPlace(ticket, place)
        val metadataOnly = status(stopped = false, uploadOnly = true, metadataKnown = false)
        session.noteNativeResult(ticket, LocalAddResult.FetchingMetadata(metadataOnly), place)
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            session.reconcileInventory(listOf(metadataOnly), 11_000L))
        assertEquals(UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED,
            session.coordinator.phase)
        assertFalse(session.finishReviewedAttempt())
        assertNull("A valid existing job does not authorize another Add",
            session.coordinator.beginSubmission(12_000L))
        assertTrue(session.acknowledgeObservedJob())
        assertNull(session.attemptedTicket)
        assertEquals(UniversalAddCoordinator.Phase.EMPTY, session.coordinator.phase)
        assertFalse(session.acknowledgeObservedJob())
        assertNull("Acknowledgement alone never authorizes a new native mutation",
            session.coordinator.beginSubmission(13_000L))
        assertEquals(UniversalAddCoordinator.Readback.WRONG_TICKET,
            session.reconcile(ticket, metadataOnly, place, 14_000L))
    }

    @Test fun unknownOrWrongPlaceDoesNotUnlockPeerlessMagnetAcknowledgement() {
        val (session, ticket) = prepared(start = true)
        session.bindPlace(ticket, place)
        val metadataOnly = status(stopped = false, uploadOnly = true, metadataKnown = false)
        session.noteNativeResult(ticket, LocalAddResult.FetchingMetadata(metadataOnly), place)
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            session.reconcileInventory(
                listOf(metadataOnly.copy(savePath = File(root, "somewhere-else").path)), 11_000L))
        assertEquals(UniversalAddCoordinator.Phase.OBSERVED_UNCERTAIN,
            session.coordinator.phase)
        assertFalse(session.acknowledgeObservedJob())
        assertNotNull(session.attemptedTicket)
        assertEquals(UniversalAddCoordinator.Readback.NEEDS_FRESH_INVENTORY,
            session.reconcile(ticket, metadataOnly, place, 9_999L))
        assertFalse(session.acknowledgeObservedJob())
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            session.reconcileInventory(listOf(metadataOnly), 13_000L))
        assertEquals(UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED,
            session.coordinator.phase)
        assertTrue(session.acknowledgeObservedJob())
    }

    @Test fun newerCompleteLocalInventoryRevokesExistingSessionAcknowledgementOnDisappearance() {
        val (session, ticket) = prepared()
        session.bindPlace(ticket, place)
        val original = status(nativeToken = "original")
        val unowned = original.copy(nativeReviewToken = "pre-existing")
        session.noteNativeResult(ticket, LocalAddResult.AddedStopped(original), place)
        assertEquals(UniversalAddCoordinator.Readback.EXACT_SESSION_CONFIRMED,
            session.reconcileInventory(listOf(unowned), 11_000L))
        assertEquals(UniversalAddCoordinator.Phase.SESSION_CONFIRMED,
            session.coordinator.phase)

        assertEquals(UniversalAddCoordinator.Readback.NOT_OBSERVED,
            session.reconcileInventory(emptyList(), 12_000L))
        assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED,
            session.coordinator.phase)
        assertFalse(session.finishReviewedAttempt())
        assertSame(ticket, session.attemptedTicket)
        assertNull("Missing session cannot authorize a second local Add",
            session.coordinator.beginSubmission(12_001L))
        assertEquals(UniversalAddCoordinator.Readback.NEEDS_FRESH_INVENTORY,
            session.reconcileInventory(listOf(unowned), 11_500L))
        assertEquals(UniversalAddCoordinator.Readback.EXACT_SESSION_CONFIRMED,
            session.reconcileInventory(listOf(unowned), 13_000L))
        assertTrue(session.finishReviewedAttempt())
    }

    @Test fun duplicateHashInNewerAcceptedInventoryRevokesPriorStartUnverifiedAcknowledgement() {
        val (session, ticket) = prepared(start = true)
        session.bindPlace(ticket, place)
        val metadataOnly = status(stopped = false, uploadOnly = true, metadataKnown = false)
        session.noteNativeResult(ticket, LocalAddResult.FetchingMetadata(metadataOnly), place)
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            session.reconcileInventory(listOf(metadataOnly), 11_000L))
        assertEquals(UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED,
            session.coordinator.phase)

        // A later accepted full list cannot establish a UNIQUE exact native
        // job. Returning a naked OBSERVED_UNVERIFIED enum here would leave
        // the earlier acknowledgement button incorrectly enabled.
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            session.reconcileInventory(listOf(metadataOnly,
                metadataOnly.copy(nativeReviewToken = "duplicate")), 12_000L))
        // Present-but-ambiguous evidence is uncertain, not an absent job.
        assertEquals(UniversalAddCoordinator.Phase.OBSERVED_UNCERTAIN,
            session.coordinator.phase)
        assertFalse(session.acknowledgeObservedJob())
        assertSame(ticket, session.attemptedTicket)
        assertNull("A contradiction never permits a blind Add retry",
            session.coordinator.beginSubmission(12_001L))

        // Older positive evidence cannot restore the CTA.
        assertEquals(UniversalAddCoordinator.Readback.NEEDS_FRESH_INVENTORY,
            session.reconcileInventory(listOf(metadataOnly), 11_500L))
        assertFalse(session.acknowledgeObservedJob())

        // A newer unique exact job at the frozen Place may restore the
        // explicit acknowledgment without claiming requested payload Start.
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            session.reconcileInventory(listOf(metadataOnly), 13_000L))
        assertEquals(UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED,
            session.coordinator.phase)
        assertTrue(session.acknowledgeObservedJob())
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
