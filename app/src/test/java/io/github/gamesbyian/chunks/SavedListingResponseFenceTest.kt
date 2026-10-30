package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentId
import org.junit.Assert.*
import org.junit.Test

class SavedListingResponseFenceTest {
    private val first = EnvironmentId("server-one")
    private val second = EnvironmentId("server-two")
    private val firstScope = "https://one.test/RPC2\u0000alice"
    private val changedScope = "https://one.test/RPC2\u0000bob"
    private val secondScope = "https://two.test/RPC2\u0000alice"

    @Test fun duplicateTorrentHashesOnDifferentProfilesDoNotShareReadIdentity() {
        val fence = SavedListingResponseFence()
        val a = fence.begin(SavedListingResponseFence.Source(first, firstScope))
        val b = fence.begin(SavedListingResponseFence.Source(second, secondScope))
        val active = mapOf(first to firstScope, second to secondScope)
        assertTrue(fence.accepts(a, active))
        assertTrue(fence.accepts(b, active))
        // A later refresh of one profile does not retire the other profile.
        val newer = fence.begin(SavedListingResponseFence.Source(first, firstScope))
        assertFalse(fence.accepts(a, active))
        assertTrue(fence.accepts(newer, active))
        assertTrue(fence.accepts(b, active))
    }

    @Test fun identityChangeAndPrivacyLockRejectLateResponses() {
        val fence = SavedListingResponseFence()
        val previous = fence.begin(SavedListingResponseFence.Source(first, firstScope))
        assertFalse(fence.accepts(previous, mapOf(first to changedScope)))
        assertFalse(fence.accepts(previous, emptyMap())) // locked, disabled or deleted
        fence.retireOutside(emptyMap())
        // Even if this profile reappears later with the same scope, its old
        // pre-lock read cannot be promoted to a new authenticated observation.
        assertFalse(fence.accepts(previous, mapOf(first to firstScope)))
        val reopened = fence.begin(SavedListingResponseFence.Source(first, firstScope))
        assertTrue(fence.accepts(reopened, mapOf(first to firstScope)))
    }

    @Test fun retiringOneProfileDoesNotCancelOtherIndependentRead() {
        val fence = SavedListingResponseFence()
        val a = fence.begin(SavedListingResponseFence.Source(first, firstScope))
        val b = fence.begin(SavedListingResponseFence.Source(second, secondScope))
        fence.retireOutside(mapOf(second to secondScope))
        assertFalse(fence.accepts(a, mapOf(first to firstScope, second to secondScope)))
        assertTrue(fence.accepts(b, mapOf(second to secondScope)))
    }

    @Test fun oldResponseCannotOverwriteNewerRequestEvenIfSourceIsIdentical() {
        val fence = SavedListingResponseFence()
        val source = SavedListingResponseFence.Source(first, firstScope)
        val pending = fence.begin(source)
        val latest = fence.begin(source)
        assertFalse(fence.accepts(pending, mapOf(first to firstScope)))
        assertTrue(fence.accepts(latest, mapOf(first to firstScope)))
    }
}
