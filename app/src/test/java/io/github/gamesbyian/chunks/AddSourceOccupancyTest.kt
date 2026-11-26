package io.github.gamesbyian.chunks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure Add occupancy decisions extracted from `ChunksApp` (A2611-02). */
class AddSourceOccupancyTest {
    private fun occupied(
        reading: Boolean = false, text: String = "", incoming: String? = null, local: Boolean = false,
        handoff: Boolean = false, remoteFile: Boolean = false, submitted: Boolean = false,
        attempted: Boolean = false, protocolHeld: Boolean = false
    ) = AddSourceOccupancy.occupied(reading, text, incoming, local, handoff, remoteFile, submitted, attempted, protocolHeld)

    @Test fun anEmptyAddHoldsNoSource() = assertFalse(occupied())

    @Test fun eachKindOfHeldSourceOccupiesTheAddOnItsOwn() {
        assertTrue(occupied(reading = true))
        assertTrue(occupied(text = "magnet:?xt=urn:btih:x"))
        assertTrue(occupied(local = true))
        assertTrue(occupied(handoff = true))
        assertTrue(occupied(remoteFile = true))
        assertTrue(occupied(submitted = true))
        assertTrue(occupied(attempted = true))
        assertTrue(occupied(protocolHeld = true))
    }

    @Test fun anIncomingMagnetEqualToTheDraftIsNotACompetingDraft() {
        assertFalse(occupied(text = "magnet:?xt=urn:btih:x", incoming = "magnet:?xt=urn:btih:x"))
        assertTrue(occupied(text = "magnet:?xt=urn:btih:x", incoming = "magnet:?xt=urn:btih:y"))
    }

    @Test fun blankDraftTextNeverOccupies() = assertFalse(occupied(text = "   ", incoming = "other"))

    private fun releasable(
        selected: Boolean = true, submitted: Boolean = true, newDoc: Boolean = false,
        deletion: Boolean = false, deleting: Boolean = false, verified: Boolean = true
    ) = AddSourceOccupancy.localVerifiedAttemptReleasable(selected, submitted, newDoc, deletion, deleting, verified)

    @Test fun aVerifiedSubmittedLocalAttemptIsReleasable() = assertTrue(releasable())

    @Test fun anyOutstandingDependencyBlocksRelease() {
        assertFalse(releasable(selected = false))
        assertFalse(releasable(submitted = false))
        assertFalse(releasable(newDoc = true))
        assertFalse("pending original-document deletion", releasable(deletion = true))
        assertFalse("running original-document deletion", releasable(deleting = true))
        assertFalse("an unverified (uncertain or in-flight) attempt is never released", releasable(verified = false))
    }
}
