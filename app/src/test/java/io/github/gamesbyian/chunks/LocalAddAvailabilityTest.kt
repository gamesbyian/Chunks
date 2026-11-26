package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every condition that keeps the Local Add control unavailable has its own witness. */
class LocalAddAvailabilityTest {
    private fun magnet(
        busy: Boolean = false, reading: Boolean = false, inventory: Boolean = true, saving: Boolean = false,
        place: Boolean = true, valid: Boolean = true, alias: Boolean = true, unresolved: Boolean = false
    ) = LocalAddAvailability.magnetUnavailableReason(busy, reading, inventory, saving, place, valid, alias, unresolved)

    private fun torrent(
        review: Boolean = true, destination: Boolean = true, busy: Boolean = false, current: Boolean = true,
        alias: Boolean = true, inventory: Boolean = true, placeSaving: Boolean = false, place: Boolean = true,
        unresolved: Boolean = false, deleting: Boolean = false
    ) = LocalAddAvailability.torrentReady(review, destination, busy, current, alias, inventory, placeSaving,
        place, unresolved, deleting)

    @Test fun aReadyMagnetHasNoReason() = assertNull(magnet())

    @Test fun eachMagnetConditionNamesItsOwnReason() {
        assertEquals("A local operation is in progress.", magnet(busy = true))
        assertEquals("Waiting for source review.", magnet(reading = true))
        assertEquals("Waiting for a fresh confirmed local transfer list.", magnet(inventory = false))
        assertEquals("Waiting for local settings to settle.", magnet(saving = true))
        assertEquals("Choose a verified local download location.", magnet(place = false))
        assertEquals("Review a valid magnet.", magnet(valid = false))
        assertEquals("Review the display name.", magnet(alias = false))
        assertEquals("Finish the previous Add before adding another.", magnet(unresolved = true))
    }

    @Test fun theFirstUnmetMagnetConditionWins() {
        assertEquals("A local operation is in progress.", magnet(busy = true, inventory = false, place = false))
        assertEquals("Waiting for a fresh confirmed local transfer list.", magnet(inventory = false, place = false))
        assertEquals("Choose a verified local download location.", magnet(place = false, valid = false))
    }

    @Test fun aReviewedTorrentIsReadyOnlyWhenEveryConditionHolds() {
        assertTrue(torrent())
        assertFalse(torrent(review = false))
        assertFalse(torrent(destination = false))
        assertFalse(torrent(busy = true))
        assertFalse("a source that changed after review", torrent(current = false))
        assertFalse(torrent(alias = false))
        assertFalse("no fresh confirmed local list", torrent(inventory = false))
        assertFalse(torrent(placeSaving = true))
        assertFalse("no verified Place", torrent(place = false))
        assertFalse("a previous attempt is unresolved", torrent(unresolved = true))
        assertFalse("the original document is being deleted", torrent(deleting = true))
    }
}
