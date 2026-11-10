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
    private val phone = MediaTransferKey("phone|" + "a".repeat(40))
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
        session.replace(listOf(phone to "Hackers.1995", secondProfile to "Away.We.Go.2009")) {
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
                if (entries.first().first == phone) {
                    initial.complete(Unit)
                    try { finish.await() } catch (_: kotlinx.coroutines.CancellationException) {
                        // Model a blocking external client returning after cancellation.
                    }
                }
                if (witness(entries.first().first)) observed.add(entries.first().first)
            })
        session.replace(listOf(phone to "Hackers.1995")) { true }
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
        session.replace(listOf(phone to "Babylon.5.S02E07")) { true }
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
        session.replace(listOf(phone to "Hackers.1995")) { true }
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
        session.replace(listOf(phone to "Hackers.1995", phone to "Duplicate")) { error("Locked") }
        assertEquals(1, calls)
        assertEquals(0, authorized)
        session.replace(emptyList()) { true }
        session.refresh()
        assertEquals(1, calls)
        session.close()
    }
}
