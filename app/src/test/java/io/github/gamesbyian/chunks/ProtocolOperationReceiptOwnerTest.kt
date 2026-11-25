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

    @Test fun policyAndExplicitCommandExcludeEachOtherOnlyForTheSameJob() {
        val owner = ProtocolOperationReceiptOwner()
        val policy = requireNotNull(owner.reservePolicy(key.profileId, key.hash.uppercase(), ProtocolOperationKind.FINISH_IN_ORDER))
        assertNull(owner.begin(key, "g1", ProtocolOperationKind.STOP))
        assertNull(owner.reservePolicy(key.profileId, key.hash, ProtocolOperationKind.SMART_SEEDING))
        assertNotNull(owner.begin(sameHashOther, "g1", ProtocolOperationKind.STOP))
        policy.close()

        val command = requireNotNull(owner.begin(key, "g1", ProtocolOperationKind.STOP))
        assertTrue(owner.possibleWrite(command))
        owner.cancel(command)
        assertNull(owner.reservePolicy(key.profileId, key.hash, ProtocolOperationKind.FINISH_IN_ORDER))
        policy.close()
        assertEquals(phase, owner.receipt(key)?.phase)
        assertTrue(owner.reconcile(command, present = true))
        owner.reservePolicy(key.profileId, key.hash, ProtocolOperationKind.FINISH_IN_ORDER)?.use {
            assertNull(owner.begin(key, "g2", ProtocolOperationKind.FILE_PRIORITIES))
        } ?: fail("Read-only reconciliation should release the command block")
        assertNotNull(owner.begin(key, "g2", ProtocolOperationKind.FILE_PRIORITIES))
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

    @Test fun aDepartingDetailsRouteCanRevokePreflightButCannotSettleAnActiveWrite() {
        val owner = ProtocolOperationReceiptOwner()
        val preflight = requireNotNull(owner.begin(key, "g1", ProtocolOperationKind.REMOVE))
        owner.abandonPreflight(preflight)
        assertFalse(owner.possibleWrite(preflight))

        val active = requireNotNull(owner.begin(key, "g1", ProtocolOperationKind.REMOVE))
        assertTrue(owner.possibleWrite(active))
        owner.abandonPreflight(active)
        assertNull(owner.begin(key, "g2", ProtocolOperationKind.REMOVE))
        assertFalse(owner.reconcile(active, present = false))
        assertEquals(ProtocolOperationReceiptOwner.Phase.POSSIBLE_WRITE, owner.receipt(key)?.phase)

        owner.cancel(active)
        assertEquals(phase, owner.receipt(key)?.phase)
        assertTrue(owner.reconcile(active, present = false))
    }

    private class MemoryJournal(var stored: List<ProtocolOperationReceiptOwner.Persisted> = emptyList(),
                                var failSaves: Boolean = false) : ProtocolOperationReceiptOwner.Journal {
        override fun load() = stored
        override fun save(entries: List<ProtocolOperationReceiptOwner.Persisted>): Boolean {
            if (failSaves) return false
            stored = entries
            return true
        }
    }

    @Test fun possibleWriteSurvivesProcessDeathAsUnknownAndBlocksWrites() {
        val journal = MemoryJournal()
        val first = ProtocolOperationReceiptOwner().also { it.attach(journal) }
        val ticket = requireNotNull(first.begin(key, "generation-1", ProtocolOperationKind.REMOVE_DATA))
        assertTrue(first.possibleWrite(ticket))
        assertEquals(listOf("POSSIBLE_WRITE"), journal.stored.map { it.phase })
        assertFalse(journal.stored.toString().contains("generation"))

        // New process: nothing in RAM, only the journal.
        val second = ProtocolOperationReceiptOwner().also { it.attach(journal) }
        val restored = requireNotNull(second.receipt(key))
        assertEquals(ProtocolOperationReceiptOwner.Phase.UNKNOWN, restored.phase)
        assertEquals(ProtocolOperationKind.REMOVE_DATA, restored.ticket.kind)
        assertNull(second.begin(key, "generation-2", ProtocolOperationKind.REMOVE))
        assertEquals(listOf("UNKNOWN"), journal.stored.map { it.phase })
        // A same-hash receipt of another profile is independent.
        assertNotNull(second.begin(sameHashOther, "generation-2", ProtocolOperationKind.START))

        // Only an explicit exact read releases it, and the release is durable.
        assertTrue(second.reconcile(restored.ticket, present = false))
        assertEquals(emptyList<ProtocolOperationReceiptOwner.Persisted>(), journal.stored)
        assertNotNull(second.begin(key, "generation-2", ProtocolOperationKind.REMOVE))
    }

    @Test fun writeIsRefusedWhenTheJournalCannotBeSaved() {
        val owner = ProtocolOperationReceiptOwner().also { it.attach(MemoryJournal(failSaves = true)) }
        val ticket = requireNotNull(owner.begin(key, "generation-1", ProtocolOperationKind.REMOVE_DATA))
        assertFalse(owner.possibleWrite(ticket))
        assertEquals(ProtocolOperationReceiptOwner.Phase.PREFLIGHT, owner.receipt(key)?.phase)
    }

    @Test fun verifiedOutcomeClearsTheJournalAndCorruptRecordsAreIgnored() {
        val journal = MemoryJournal(listOf(
            ProtocolOperationReceiptOwner.Persisted("p", "not-a-hash", "REMOVE", "UNKNOWN"),
            ProtocolOperationReceiptOwner.Persisted("p", key.hash, "NOPE", "UNKNOWN"),
            ProtocolOperationReceiptOwner.Persisted("p", key.hash, "REMOVE", "RECONCILED_ABSENT")))
        val owner = ProtocolOperationReceiptOwner().also { it.attach(journal) }
        assertNull(owner.receipt(ProtocolOperationReceiptOwner.Key("p", key.hash)))
        val ticket = requireNotNull(owner.begin(key, "g", ProtocolOperationKind.STOP))
        assertTrue(owner.possibleWrite(ticket))
        owner.complete(ticket, ClientOperationOutcome.Verified(key.hash, null))
        assertEquals(emptyList<ProtocolOperationReceiptOwner.Persisted>(), journal.stored)
    }
}
