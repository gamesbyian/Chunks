package io.github.gamesbyian.chunks.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Navigation continuity without widening authority: pure lease contract, no HTTP. */
class MediaInventoryHostLeaseTest {
    private val key = MediaTransferKey("phone|" + "a".repeat(40))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** A scan that stays in flight until released, recording whether it was allowed to commit. */
    private class Probe {
        val release = CompletableDeferred<Unit>()
        val committed = mutableListOf<Boolean>()
        var opened = 0
    }

    private fun open(probe: Probe) = MediaInventoryEnrichmentSession(scope, enrich = { entries, allowed ->
        try { probe.release.await() } catch (_: kotlinx.coroutines.CancellationException) {}
        probe.committed += allowed(entries.first().first)
    }).also { probe.opened++ }

    @Test fun leavingTransfersKeepsAnAuthorizedScanAndReattachReusesTheSameSession() {
        val probe = Probe()
        val lease = MediaInventoryHostLease()
        val first = lease.attach { open(probe) }
        lease.revokeUnless("epoch1|account", 0L)
        first.replace(listOf(key to "Hackers.1995")) { true }
        lease.detach(first, nowMillis = 1_000L)
        lease.revokeUnless("epoch1|account", 61_000L)
        assertFalse("Navigation alone must not cancel the authorized scan", first.isClosed)
        assertSame(first, lease.attach { open(probe) })
        assertEquals(1, probe.opened)
        probe.release.complete(Unit)
        assertEquals(listOf(true), probe.committed)
    }

    @Test fun anyAuthorityChangeWhileDetachedRevokesInFlightWork() {
        for (changed in listOf("epoch2|account", "epoch1|other-account", "epoch1|account|forgotten")) {
            val probe = Probe()
            val lease = MediaInventoryHostLease()
            val session = lease.attach { open(probe) }
            lease.revokeUnless("epoch1|account", 0L)
            session.replace(listOf(key to "Hackers.1995")) { true }
            lease.detach(session, 1_000L)
            lease.revokeUnless(changed, 2_000L)
            assertTrue("$changed must close the detached session", session.isClosed)
            probe.release.complete(Unit)
            assertEquals("$changed: revoked work never commits", listOf(false), probe.committed)
            val next = lease.attach { open(probe) }
            assertTrue("A revoked session is never handed back", next !== session)
        }
    }

    @Test fun detachedWindowIsBoundedAndAttachedOwnershipIsNeverRevokedByTheLease() {
        val probe = Probe()
        val lease = MediaInventoryHostLease(maxDetachedMillis = 600_000L)
        val session = lease.attach { open(probe) }
        lease.revokeUnless("w", 0L)
        // While attached, the Transfers host owns revocation through its own snapshot.
        lease.revokeUnless("changed-while-attached", 5_000L)
        assertFalse(session.isClosed)
        lease.detach(session, 10_000L)
        lease.revokeUnless("changed-while-attached", 609_999L)
        assertFalse(session.isClosed)
        lease.revokeUnless("changed-while-attached", 610_000L)
        assertTrue("The detached window must expire", session.isClosed)
    }

    @Test fun closingTheAppOwnerClosesEvenADetachedSession() {
        val probe = Probe()
        val lease = MediaInventoryHostLease()
        val session = lease.attach { open(probe) }
        lease.detach(session, 0L)
        lease.close()
        assertTrue(session.isClosed)
    }
}
