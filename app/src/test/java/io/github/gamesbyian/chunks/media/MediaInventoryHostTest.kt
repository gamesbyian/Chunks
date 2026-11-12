package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1453: the inventory witness outlives the Transfers screen. The screen only feeds it;
 * leaving Transfers must not cancel offscreen work, while a privacy generation change must.
 */
class MediaInventoryHostTest {
    private val phone = EnvironmentId("phone")
    private val remote = EnvironmentId("rtorrent:profile-2")
    private val hash = "a".repeat(40)
    private val scopes = mapOf(phone to "phone", remote to "user@profile-2")

    private fun source(env: EnvironmentId, name: String) =
        EnvironmentTransferSnapshot(env, EnvironmentAvailability.LIVE, true, 1234L,
            listOf(UnifiedTransferObservation(UnifiedTransferKey.of(env, hash), name, name,
                null, null, null, null, null, null)))

    private fun snapshot(epoch: String, vararg names: Pair<EnvironmentId, String>) =
        AuthorizedMediaInventorySnapshot.build(names.map { source(it.first, it.second) }, scopes, epoch)

    private fun key(env: EnvironmentId) = MediaTransferKey.of(UnifiedTransferKey.of(env, hash))

    private class Harness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val committed = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var closedSessions = 0
        val session = MediaInventoryEnrichmentSession(scope,
            enrich = { entries, witness ->
                entered.complete(Unit)
                release.await()
                entries.forEach { (key, name) -> if (witness(key)) committed.add(name) }
            },
            onClosed = { closedSessions++ })
        val host = MediaInventoryHost(session)
    }

    @Test fun leavingTheFeedingScreenDoesNotCancelTheRetainedInventory() = runBlocking {
        val h = Harness()
        h.host.feed(snapshot("epoch-1", phone to "Hackers.1995", remote to "Away.We.Go.2009"))
        h.entered.await()
        // The Transfers screen unmounts: nothing is closed or replaced here, unlike the
        // former DisposableEffect-owned session. The pass continues and commits.
        h.release.complete(Unit)
        assertEquals(listOf("Hackers.1995", "Away.We.Go.2009"), h.committed)
        assertEquals(0, h.closedSessions)
        assertTrue(h.host.retained?.entries?.size == 2)
    }

    @Test fun privacyGenerationChangeRevokesInFlightWorkUntilTheNextFreshFeed() = runBlocking {
        val h = Harness()
        h.host.feed(snapshot("epoch-1", phone to "Hackers.1995"))
        h.entered.await()
        h.host.privacyEpoch("epoch-2")
        assertNull("A lock/unlock or policy change empties the retained witness", h.host.retained)
        h.release.complete(Unit)
        assertEquals("Old callbacks must not commit after revocation", emptyList<String>(), h.committed)
        // The stale screen read built under epoch-1 cannot resurrect authority.
        h.host.feed(snapshot("epoch-1", phone to "Hackers.1995"))
        assertNull(h.host.retained)
        // A fresh observation under the current generation resumes offscreen work.
        h.host.feed(snapshot("epoch-2", phone to "Hackers.1995"))
        assertEquals(listOf("Hackers.1995"), h.committed)
    }

    @Test fun witnessFollowsTheLatestFeedNotTheSnapshotThatStartedThePass() = runBlocking {
        val h = Harness()
        h.host.feed(snapshot("epoch-1", phone to "Hackers.1995", remote to "Away.We.Go.2009"))
        h.entered.await()
        // The remote profile disappears from the next LIVE read (sign-out, profile removal).
        // replace() starts a new pass only for the phone row; the old pass's witness now
        // refuses the remote key even if its blocking request returns late.
        h.host.feed(snapshot("epoch-1", phone to "Hackers.1995"))
        h.release.complete(Unit)
        assertFalse(h.committed.contains("Away.We.Go.2009"))
        assertTrue(h.committed.contains("Hackers.1995"))
        assertEquals(setOf(key(phone)), h.host.retained?.entries?.map { it.first }?.toSet())
    }

    @Test fun closeEndsTheSessionAndLaterFeedsAreIgnored() = runBlocking {
        val h = Harness()
        h.host.feed(snapshot("epoch-1", phone to "Hackers.1995"))
        h.entered.await()
        h.host.close()
        assertEquals(1, h.closedSessions)
        h.release.complete(Unit)
        assertEquals(emptyList<String>(), h.committed)
        h.host.feed(snapshot("epoch-1", phone to "Hackers.1995"))
        assertNull(h.host.retained)
    }
}
