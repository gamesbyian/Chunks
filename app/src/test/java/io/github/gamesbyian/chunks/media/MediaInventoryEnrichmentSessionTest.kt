package io.github.gamesbyian.chunks.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class MediaInventoryEnrichmentSessionTest {
    private val local = MediaTransferKey("local|" + "a".repeat(40))
    private val secondProfile = MediaTransferKey("rtorrent:other|" + "a".repeat(40))

    @Test fun fullInventoryRunsWithoutAnyComposedRowsAndRechecksCurrentWitness() {
        val allowed = AtomicBoolean(true)
        val seen = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = MediaInventoryEnrichmentSession(scope,
            enrich = { entries, witness ->
                entries.forEach { (key, name) ->
                    if (witness(key)) seen.add(name)
                }
            })
        session.replace(listOf(local to "Hackers.1995", secondProfile to "Away.We.Go.2009")) {
            allowed.get()
        }
        assertEquals(listOf("Hackers.1995", "Away.We.Go.2009"), seen)
        allowed.set(false)
        session.refresh()
        assertEquals("Settings refresh must not borrow a revoked profile", 2, seen.size)
        session.close()
        session.refresh()
        assertEquals(2, seen.size)
    }

    @Test fun oldProfileCannotCommitAfterReplacementEvenIfHttpIgnoresCancellation() = runBlocking {
        val initial = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val observed = mutableListOf<MediaTransferKey>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = MediaInventoryEnrichmentSession(scope,
            enrich = { entries, witness ->
                if (entries.first().first == local) {
                    initial.complete(Unit)
                    try { finish.await() } catch (_: kotlinx.coroutines.CancellationException) {
                        // Model a blocking external client returning after cancellation.
                    }
                }
                if (witness(entries.first().first)) observed.add(entries.first().first)
            })
        session.replace(listOf(local to "Hackers.1995")) { true }
        initial.await()
        session.replace(listOf(secondProfile to "Away.We.Go.2009")) { true }
        finish.complete(Unit)
        assertEquals(listOf(secondProfile), observed)
        session.close()
    }

    @Test fun multipleRefreshesCannotLoseLastAuthorizedRetryWhileOldHttpUnwinds() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val finishHttp = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var attempts = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = MediaInventoryEnrichmentSession(scope,
            enrich = { entries, witness ->
                attempts++
                if (attempts == 1) {
                    events.add("old-enter")
                    firstStarted.complete(Unit)
                    // Model blocking HTTPS that ignores cancellation until its
                    // own completion. The controller's exact-key pending latch
                    // is still held throughout this period.
                    withContext(NonCancellable) { finishHttp.await() }
                    events.add("old-return")
                } else if (witness(entries.single().first)) {
                    events.add("authorized-retry")
                }
            })
        session.replace(listOf(local to "Babylon.5.S02E07")) { true }
        firstStarted.await()
        // A consent/network transition, followed by a quick second inventory
        // refresh, must not race the previous provider's in-flight exact key.
        session.refresh()
        session.refresh()
        assertEquals(listOf("old-enter"), events)
        assertEquals(1, attempts)
        finishHttp.complete(Unit)
        assertEquals(listOf("old-enter", "old-return", "authorized-retry"), events)
        assertEquals("Only the latest pending request should run", 2, attempts)
        session.close()
    }

    @Test fun repeatedHeartbeatsDoNotStarveTailOfLongAuthorizedFullInventoryScan() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val releaseSlowRequest = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var passes = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = MediaInventoryEnrichmentSession(scope,
            enrich = { entries, witness ->
                passes++
                if (passes == 1) {
                    events.add("first-pass-start")
                    started.complete(Unit)
                    // An earlier provider HTTP request may ignore cancellation
                    // while dozens of inventory/status heartbeat ticks arrive.
                    withContext(NonCancellable) { releaseSlowRequest.await() }
                }
                entries.forEach { (key, name) ->
                    if (witness(key)) events.add("pass$passes:$name")
                }
            })
        session.replace(listOf(local to "Beginning", secondProfile to "Tail")) { true }
        started.await()
        repeat(100) { session.refresh() }
        assertEquals("No heartbeat can interrupt an otherwise authorized scan", 1, passes)
        assertEquals(listOf("first-pass-start"), events)
        releaseSlowRequest.complete(Unit)
        assertEquals("All rows in the original scan must finish first, then one catch-up",
            listOf("first-pass-start", "pass1:Beginning", "pass1:Tail",
                "pass2:Beginning", "pass2:Tail"), events)
        assertEquals("A hundred ticks must coalesce to one bounded extra pass", 2, passes)
        session.close()
    }

    @Test fun settingsRefreshRevokesBlockedHttpEvenIfConsentIsReenabledBeforeReturn() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        var attempts = 0
        val accepted = mutableListOf<String>()
        val session = MediaInventoryEnrichmentSession(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            enrich = { entries, witness ->
                attempts++
                if (attempts == 1) {
                    started.complete(Unit)
                    withContext(NonCancellable) { unblock.await() }
                }
                entries.forEach { (key, name) -> if (witness(key)) accepted.add(name) }
            })
        val consent = AtomicBoolean(true)
        session.replace(listOf(local to "Old HTTP body")) { consent.get() }
        started.await()
        session.refresh() // routine heartbeat is pending, not revoking
        consent.set(false)
        session.refreshRevoking() // app settings must cancel the old generation
        consent.set(true)
        unblock.complete(Unit)
        assertEquals("An old provider callback cannot commit after consent ABA",
            listOf("Old HTTP body"), accepted)
        assertEquals(2, attempts)
        session.close()
    }

    @Test fun inventoryReplacementPreemptsQueuedHeartbeatAndNeverReplaysOldAccount() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val releaseSlowRequest = CompletableDeferred<Unit>()
        val seen = mutableListOf<String>()
        val session = MediaInventoryEnrichmentSession(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            enrich = { entries, witness ->
                if (entries.first().first == local) {
                    started.complete(Unit)
                    withContext(NonCancellable) { releaseSlowRequest.await() }
                }
                entries.forEach { (key, name) -> if (witness(key)) seen.add(name) }
            })
        session.replace(listOf(local to "Old private account")) { true }
        started.await()
        repeat(4) { session.refresh() }
        session.replace(listOf(secondProfile to "Current exact account")) { true }
        releaseSlowRequest.complete(Unit)
        assertEquals(listOf("Current exact account"), seen)
        session.close()
    }

    @Test fun explicitCloseRevokesInFlightWorkAndCannotBeReopened() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var attempted = false
        var closedCount = 0
        val session = MediaInventoryEnrichmentSession(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            enrich = { entries, witness ->
                ready.complete(Unit)
                try { release.await() } catch (_: kotlinx.coroutines.CancellationException) {}
                attempted = witness(entries.single().first)
            },
            onClosed = { closedCount++ })
        session.replace(listOf(local to "Hackers.1995")) { true }
        ready.await()
        session.close()
        release.complete(Unit)
        session.replace(listOf(secondProfile to "Another.Title")) { true }
        session.refresh()
        session.close()
        assertFalse(attempted)
        assertEquals(1, closedCount)
    }

    @Test fun emptyOrThrowingAuthorizationFailsClosed() {
        var calls = 0
        var authorized = 0
        val session = MediaInventoryEnrichmentSession(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            enrich = { entries, witness ->
                calls++
                authorized += entries.count { witness(it.first) }
            })
        session.replace(listOf(local to "Hackers.1995", local to "Duplicate")) { error("Locked") }
        assertEquals(1, calls)
        assertEquals(0, authorized)
        session.replace(emptyList()) { true }
        session.refresh()
        assertEquals(1, calls)
        session.close()
    }
}
