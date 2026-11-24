package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.ClientOperationOutcome
import org.junit.Assert.*
import org.junit.Test

class ProtocolOperationReceiptOwnerTest {
    private val key = ProtocolOperationReceiptOwner.Key("profile-a", "ab".repeat(20))
    private val sameHashOther = key.copy(profileId = "profile-b")
    private val phase = ProtocolOperationReceiptOwner.Phase.UNKNOWN

    @Test fun cancellationBeforeAdapterEntryReleasesButAfterEntryRetainsUnknown() {
        val owner = ProtocolOperationReceiptOwner()
        val preflight = requireNotNull(owner.begin(key, "generation-1", ProtocolOperationKind.START))
        owner.cancel(preflight)
        assertNull(owner.receipt(key))
        val dispatched = requireNotNull(owner.begin(key, "generation-1", ProtocolOperationKind.START))
        assertTrue(owner.possibleWrite(dispatched))
        assertFalse(owner.possibleWrite(dispatched))
        owner.cancel(dispatched)
        assertEquals(phase, owner.receipt(key)?.phase)
        assertNull(owner.begin(key, "generation-2", ProtocolOperationKind.STOP))
        // An unrelated account's identical hash is independent.
        assertNotNull(owner.begin(sameHashOther, "generation-2", ProtocolOperationKind.STOP))
        assertTrue(owner.reconcile(dispatched, present = true))
        assertEquals(ProtocolOperationReceiptOwner.Phase.RECONCILED_PRESENT, owner.receipt(key)?.phase)
        val next = requireNotNull(owner.begin(key, "generation-2", ProtocolOperationKind.STOP))
        assertFalse(owner.reconcile(dispatched, present = false))
        owner.complete(dispatched, ClientOperationOutcome.Verified(key.hash, null))
        assertSame(next, owner.receipt(key)?.ticket)
    }

    @Test fun unverifiedPayloadDeletionRetainsReceiptWithoutStoringServerResponse() {
        val owner = ProtocolOperationReceiptOwner()
        val ticket = requireNotNull(owner.begin(key, "generation-1", ProtocolOperationKind.REMOVE_DATA))
        assertTrue(owner.possibleWrite(ticket))
        owner.complete(ticket, ClientOperationOutcome.Pending(key.hash, null, "/private/path?secret=x",
            registrationAbsent = true))
        val receipt = requireNotNull(owner.receipt(key))
        assertEquals(ProtocolOperationReceiptOwner.Phase.REGISTRATION_ABSENT, receipt.phase)
        assertTrue(receipt.blocksWrites)
        assertFalse(receipt.description().contains("secret"))
        assertTrue(owner.reconcile(ticket, present = false))
        assertTrue(requireNotNull(owner.receipt(key)).description().contains("not independently verified"))
    }

    @Test fun capacityNeverSilentlyEvictsUnresolvedReceipts() {
        val owner = ProtocolOperationReceiptOwner(capacity = 1)
        val ticket = requireNotNull(owner.begin(key, "generation-1", ProtocolOperationKind.START))
        owner.possibleWrite(ticket)
        owner.cancel(ticket)
        assertNull(owner.begin(sameHashOther, "generation-2", ProtocolOperationKind.STOP))
        assertTrue(owner.reconcile(ticket, present = false))
        assertNotNull(owner.begin(sameHashOther, "generation-2", ProtocolOperationKind.STOP))
        assertNull(owner.receipt(key))
    }
}
