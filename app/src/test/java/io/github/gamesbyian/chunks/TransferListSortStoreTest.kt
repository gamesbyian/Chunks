package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.LocalQueueOrder
import io.github.gamesbyian.chunks.model.TransferOrder
import io.github.gamesbyian.chunks.model.UnifiedSortDirection
import org.junit.Assert.*
import org.junit.Test

class TransferListSortStoreTest {
    @Test fun roundTripsKeyAndDirectionAndRejectsUnknownValues() {
        val encoded = TransferListSortStore.encode(TransferOrder.SIZE, UnifiedSortDirection.ASCENDING)
        assertEquals(TransferOrder.SIZE to UnifiedSortDirection.ASCENDING,
            TransferListSortStore.decode(encoded, TransferOrder.entries.toTypedArray()))
        // A server key that the phone list does not offer falls back, never guesses.
        assertNull(TransferListSortStore.decode("SPEED:ASCENDING", LocalQueueOrder.entries.toTypedArray()))
        assertNull(TransferListSortStore.decode("SIZE:SIDEWAYS", TransferOrder.entries.toTypedArray()))
        assertNull(TransferListSortStore.decode("SIZE", TransferOrder.entries.toTypedArray()))
        assertNull(TransferListSortStore.decode(null, TransferOrder.entries.toTypedArray()))
    }

    @Test fun onlyStableEnvironmentIdentitiesAreAccepted() {
        assertTrue(TransferListSortStore.validEnvironment("phone"))
        assertTrue(TransferListSortStore.validEnvironment(
            RememberedRemoteProfileRoster.identityOf("https://example.org/RPC2", "user")))
        assertFalse(TransferListSortStore.validEnvironment(""))
        assertFalse(TransferListSortStore.validEnvironment("https://example.org/RPC2"))
    }
}
