package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.rpc.RemoteProbe
import io.github.gamesbyian.chunks.rpc.RemoteStallEvidence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class CombinedDetailProbeOwnerTest {
    private val client = EnvironmentId("remote-one")
    private val another = EnvironmentId("remote-two")
    private val key = UnifiedTransferKey.of(client, "a".repeat(40))
    private val otherKey = UnifiedTransferKey.of(another, key.infoHash)
    private val session = Any()
    private fun report(hash: String) = RemoteStallEvidence(hash, true, false, true,
        null, null, null, null, RemoteStallEvidence.MessageClass.NONE)

    @Test fun oldRenderedCallbacksCannotDispatchOrSupersedeCurrentClientRead() = runBlocking {
        val owner = CombinedDetailProbeOwner(this, Dispatchers.Unconfined)
        val old = owner.bind(session, client, key, true, "1")
        val next = owner.bind(Any(), another, otherKey, true, "1")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var staleReads = 0
        owner.requestCapabilities(next) {
            entered.complete(Unit)
            release.await()
            RemoteProbe("fresh", emptySet())
        }
        withTimeout(5_000) { entered.await() }
        owner.requestCapabilities(old) {
            staleReads++
            RemoteProbe("old", emptySet())
        }
        owner.requestStall(old) { hash -> staleReads++; report(hash) }
        yield()
        assertEquals(0, staleReads)
        assertTrue(owner.capabilityInProgress)
        assertFalse(owner.stallInProgress)
        release.complete(Unit)
        withTimeout(5_000) { while (owner.capabilityInProgress) yield() }
        assertEquals(another, owner.capabilitySnapshot?.environment)
        assertEquals("rTorrent fresh", owner.capabilitySnapshot?.source)
        assertNull(owner.stallReport)
    }

    @Test fun lockThenUnlockCannotResurrectReadOrRenderedCallbackEvenAtSameFocus() = runBlocking {
        val owner = CombinedDetailProbeOwner(this, Dispatchers.Unconfined)
        val old = owner.bind(session, client, key, true, "1")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        owner.requestStall(old) { hash ->
            entered.complete(Unit)
            release.await()
            report(hash)
        }
        withTimeout(5_000) { entered.await() }
        assertTrue(owner.stallInProgress)
        assertNull(owner.bind(session, client, key, false, "2"))
        assertFalse(owner.stallInProgress)
        val fresh = owner.bind(session, client, key, true, "3")
        assertNotSame(old, fresh)
        var oldCalls = 0
        owner.requestStall(old) { hash -> oldCalls++; report(hash) }
        release.complete(Unit)
        yield()
        assertEquals(0, oldCalls)
        assertNull(owner.stallReport)
        var newCalls = 0
        owner.requestStall(fresh) { hash -> newCalls++; report(hash) }
        withTimeout(5_000) { while (owner.stallInProgress) yield() }
        assertEquals(1, newCalls)
        assertEquals(key.infoHash, owner.stallReport?.hash)
    }

    @Test fun changedPrivacyEpochAndClosingDetailsInvalidateBothEvidenceFamilies() = runBlocking {
        val owner = CombinedDetailProbeOwner(this, Dispatchers.Unconfined)
        val first = owner.bind(session, client, key, true, "1")
        assertSame(first, owner.bind(session, client, key, true, "1"))
        owner.requestCapabilities(first) { RemoteProbe("one", emptySet()) }
        owner.requestStall(first) { hash -> report(hash) }
        withTimeout(5_000) {
            while (owner.capabilityInProgress || owner.stallInProgress) yield()
        }
        assertNotNull(owner.capabilitySnapshot)
        assertNotNull(owner.stallReport)
        val second = owner.bind(session, client, key, true, "2")
        assertNotSame(first, second)
        assertNull(owner.capabilitySnapshot)
        assertNull(owner.stallReport)
        assertNull(owner.bind(session, client, null, true, "2"))
        var reads = 0
        owner.requestCapabilities(second) { reads++; RemoteProbe("old", emptySet()) }
        owner.requestStall(second) { hash -> reads++; report(hash) }
        yield()
        assertEquals(0, reads)
    }

    @Test fun queuedReadIsRefusedAfterBindingChangesBeforeDispatch() = runBlocking {
        val owner = CombinedDetailProbeOwner(this, Dispatchers.Unconfined)
        val first = owner.bind(session, client, key, true, "1")
        var reads = 0
        owner.requestCapabilities(first) { reads++; RemoteProbe("old", emptySet()) }
        owner.bind(session, client, key, false, "2")
        yield()
        assertEquals(0, reads)
        assertNull(owner.capabilitySnapshot)
        assertFalse(owner.capabilityInProgress)
    }

    @Test fun currentFailuresAreFixedCopyAndDoNotExposeRpcText() = runBlocking {
        val owner = CombinedDetailProbeOwner(this, Dispatchers.Unconfined)
        val first = owner.bind(session, client, key, true, "1")
        owner.requestCapabilities(first) { error("https://private.invalid/passkey") }
        owner.requestStall(first) { error("private path") }
        withTimeout(5_000) {
            while (owner.capabilityInProgress || owner.stallInProgress) yield()
        }
        assertEquals("The signed-in remote client did not complete the read-only check.",
            owner.capabilityError)
        assertEquals("The signed-in remote client could not finish the exact transfer check.",
            owner.stallError)
        assertNull(owner.capabilitySnapshot)
        assertNull(owner.stallReport)
    }
}
