package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2611-02 step 2: the retained server-job-to-Local handoff and the post-Add
 * focus. Every case is a hazard from the abandoned first attempt (#687).
 */
class AddHandoffStateTest {
    private val hashA = "a".repeat(40)
    private val hashB = "b".repeat(40)
    private val scopeA = "https://a.example/RPC2\u0000alice"
    private val scopeB = "https://b.example/RPC2\u0000bob"
    private val sessionA = Any()
    private val sessionB = Any()
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test fun handoffIsClaimedExactlyOnceAndNeverReplayed() {
        val state = AddHandoffState()
        val staged = state.stage("magnet:?xt=urn:btih:$hashA")
        assertSame(staged, state.staged)
        assertTrue(state.claim(staged))
        assertNull(state.staged)
        assertFalse("a consumed handoff cannot be claimed again", state.claim(staged))
        assertFalse("a stale capture of the consumed handoff cannot clear a newer one", run {
            val newer = state.stage("magnet:?xt=urn:btih:$hashB")
            val stale = state.claim(staged)
            assertSame(newer, state.staged)
            stale
        })
    }

    @Test fun anUnseenHandoffNavigatesOnceThenDiesWhenTheLocalReviewIsLeft() {
        val state = AddHandoffState()
        state.stage("magnet:?xt=urn:btih:$hashA")
        // Restored to Combined: the person asked for Local, so go there; do not wait unseen.
        assertEquals(AddHandoffState.Arbitration.NAVIGATE_TO_LOCAL, state.arbitrate(localVisible = false))
        assertEquals(AddHandoffState.Arbitration.NONE, state.arbitrate(localVisible = true))
        // Left again without consuming: never replay it on a later visit.
        assertEquals(AddHandoffState.Arbitration.DROPPED, state.arbitrate(localVisible = false))
        assertNull(state.staged)
        assertEquals(AddHandoffState.Arbitration.NONE, state.arbitrate(localVisible = true))
    }

    @Test fun preparationStagesOnlyForTheExactLiveSession() = runBlocking {
        val state = AddHandoffState()
        val appScope = scope()
        var live: Pair<Any?, String> = sessionA to scopeA
        val gate = CompletableDeferred<String?>()
        val job = state.prepare(appScope, RemoteSessionStamp(sessionA, scopeA), { live }) { gate.await() }
        assertNull("nothing is staged while the lookup runs", state.staged)
        gate.complete("magnet:?xt=urn:btih:$hashA")
        job.join()
        assertEquals("magnet:?xt=urn:btih:$hashA", state.staged?.magnet)
        assertSame("the handoff remembers its session", sessionA, state.staged?.origin?.session)
        appScope.cancel()
    }

    @Test fun aLookupFinishingAfterDisconnectOrAnotherAccountStagesNothing() = runBlocking {
        for (after in listOf<Pair<Any?, String>>(null to "", sessionB to scopeB, sessionB to scopeA, sessionA to scopeB)) {
            val state = AddHandoffState()
            val appScope = scope()
            var live: Pair<Any?, String> = sessionA to scopeA
            val gate = CompletableDeferred<String?>()
            val job = state.prepare(appScope, RemoteSessionStamp(sessionA, scopeA), { live }) { gate.await() }
            live = after
            gate.complete("magnet:?xt=urn:btih:$hashA")
            job.join()
            assertNull("stale session $after", state.staged)
            appScope.cancel()
        }
    }

    @Test fun aSupersededOrDroppedPreparationNeverStagesAndIsCancelled() = runBlocking {
        val state = AddHandoffState()
        val appScope = scope()
        val live: Pair<Any?, String> = sessionA to scopeA
        val first = CompletableDeferred<String?>()
        val second = CompletableDeferred<String?>()
        val firstJob = state.prepare(appScope, RemoteSessionStamp(sessionA, scopeA), { live }) { first.await() }
        val secondJob = state.prepare(appScope, RemoteSessionStamp(sessionA, scopeA), { live }) { second.await() }
        assertTrue("the replaced preparation is cancelled", firstJob.isCancelled)
        first.complete("magnet:?xt=urn:btih:$hashA")
        second.complete("magnet:?xt=urn:btih:$hashB")
        secondJob.join()
        assertEquals("magnet:?xt=urn:btih:$hashB", state.staged?.magnet)

        val third = CompletableDeferred<String?>()
        val thirdJob = state.prepare(appScope, RemoteSessionStamp(sessionA, scopeA), { live }) { third.await() }
        state.drop()
        assertTrue(thirdJob.isCancelled)
        assertNull(state.staged)
        appScope.cancel()
    }

    @Test fun cancellationPropagatesAndLeavesTheSlotUsable() = runBlocking {
        val state = AddHandoffState()
        val appScope = scope()
        val live: Pair<Any?, String> = sessionA to scopeA
        val job = state.prepare(appScope, RemoteSessionStamp(sessionA, scopeA), { live }) {
            throw CancellationException("owner cleared")
        }
        job.join()
        assertTrue(job.isCancelled)
        assertNull(state.staged)
        val next = state.prepare(appScope, RemoteSessionStamp(sessionA, scopeA), { live }) { "magnet:?xt=urn:btih:$hashA" }
        next.join()
        assertNotNull(state.staged)
        appScope.cancel()
    }

    @Test fun aFailedLookupStagesNothingButTheSlotStaysUsable() = runBlocking {
        val state = AddHandoffState()
        val appScope = scope()
        val live: Pair<Any?, String> = sessionA to scopeA
        state.prepare(appScope, RemoteSessionStamp(sessionA, scopeA), { live }) { error("lookup failed") }.join()
        assertNull(state.staged)
        appScope.cancel()
    }

    @Test fun sessionEndRetiresPreparationAndAServerOriginHandoffButNotAnAndroidShare() = runBlocking {
        val state = AddHandoffState()
        val appScope = scope()
        val live: Pair<Any?, String> = sessionA to scopeA
        val gate = CompletableDeferred<String?>()
        val job = state.prepare(appScope, RemoteSessionStamp(sessionA, scopeA), { live }) { gate.await() }
        state.dropIfSessionChanged(sessionA, scopeA)
        assertTrue("same session keeps its preparation", job.isActive)
        state.dropIfSessionChanged(null, "")
        assertTrue(job.isCancelled)

        state.stage("magnet:?xt=urn:btih:$hashA", RemoteSessionStamp(sessionA, scopeA))
        state.dropIfSessionChanged(sessionA, scopeA)
        assertNotNull(state.staged)
        state.dropIfSessionChanged(sessionB, scopeB)
        assertNull("another account never receives the first one's server link", state.staged)

        state.stage("magnet:?xt=urn:btih:$hashB")
        state.dropIfSessionChanged(null, "")
        assertNotNull("an Android-share handoff is not account data", state.staged)
        appScope.cancel()
    }

    @Test fun aLockedScopeRevokesServerOriginHandoffButAnUnlockedWindowKeepsIt() {
        val state = AddHandoffState()
        state.stage("magnet:?xt=urn:btih:$hashA", RemoteSessionStamp(sessionA, scopeA))
        // A rotation inside an unlocked window: the gate still authorizes, nothing is revoked.
        state.revokeIfLocked { true }
        assertNotNull(state.staged)
        // Only the origin's own scope matters; another account's lock changes nothing.
        state.revokeIfLocked { it != scopeB }
        assertNotNull(state.staged)
        state.revokeIfLocked { it != scopeA }
        assertNull(state.staged)
        state.stage("magnet:?xt=urn:btih:$hashB")
        state.revokeIfLocked { false }
        assertNotNull("an Android-share handoff is not account data", state.staged)
    }

    @Test fun aLockedScopeCancelsAPreparationAndAFinishingLookupStagesNothing() = runBlocking {
        val state = AddHandoffState()
        val appScope = scope()
        val live: Pair<Any?, String> = sessionA to scopeA
        var allowed = true
        val gate = CompletableDeferred<String?>()
        val job = state.prepare(appScope, RemoteSessionStamp(sessionA, scopeA), { live }, { allowed }) { gate.await() }
        allowed = false
        gate.complete("magnet:?xt=urn:btih:$hashA")
        job.join()
        assertNull("the lock was engaged before the lookup finished", state.staged)

        allowed = true
        val gate2 = CompletableDeferred<String?>()
        val job2 = state.prepare(appScope, RemoteSessionStamp(sessionA, scopeA), { live }, { allowed }) { gate2.await() }
        state.revokeIfLocked { false }
        assertTrue(job2.isCancelled)
        appScope.cancel()
    }

    @Test fun postAddFocusIsBoundToTheExactLiveSessionAndAccount() {
        val focus = PostAddFocus()
        val record = RemoteAddedRecord(scopeA, hashA, "main|ALL")
        focus.rememberRemote(sessionA, record)
        assertEquals(record, focus.remoteFor(sessionA, scopeA))
        val key = focus.latestFor(sessionA, scopeA)
        assertNotNull(key)
        assertNull("another account", focus.remoteFor(sessionB, scopeB))
        assertNull("same account, reconnected session", focus.remoteFor(sessionB, scopeA))
        assertNull("same session object, other account scope", focus.remoteFor(sessionA, scopeB))
        assertNull("disconnected", focus.remoteFor(null, ""))
        assertNull(focus.latestFor(sessionB, scopeA))
    }

    @Test fun clearingTheServerRecordKeepsOnlyTheLocalFocus() {
        val focus = PostAddFocus()
        val local = io.github.gamesbyian.chunks.model.UnifiedTransferKey.of(EnvironmentSnapshots.LOCAL, hashA)
        focus.rememberLocal(local)
        assertEquals(local, focus.latestFor(null, ""))
        focus.rememberRemote(sessionA, RemoteAddedRecord(scopeA, hashB, "main|ALL"))
        assertNotNull(focus.latestFor(sessionA, scopeA))
        focus.clearRemote()
        assertNull(focus.remoteFor(sessionA, scopeA))
        assertNull(focus.latestFor(sessionA, scopeA))

        focus.rememberLocal(local)
        focus.rememberRemote(sessionA, RemoteAddedRecord(scopeA, hashB, "main|ALL"))
        focus.clearRemote()
        assertNull("a cleared server Add never falls back to a stale key", focus.latestFor(sessionA, scopeA))
    }

    @Test fun aLockedScopeClearsPostAddFocusButAnUnlockedWindowKeepsIt() {
        val focus = PostAddFocus()
        focus.rememberRemote(sessionA, RemoteAddedRecord(scopeA, hashA, "main|ALL"))
        focus.revokeIfLocked { true }
        assertNotNull(focus.remoteFor(sessionA, scopeA))
        focus.revokeIfLocked { false }
        assertNull(focus.remoteFor(sessionA, scopeA))
        assertNull(focus.latestFor(sessionA, scopeA))
    }
}
