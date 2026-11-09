package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.RemoteAddProgress
import io.github.gamesbyian.chunks.rpc.RemoteTrafficSample
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI-only, bounded readback recovery. No fake Add or retry-of-write API. */
class VerifiedAddReadOnlyPollingTest {
    @Test fun transientReadErrorDoesNotEndFutureExactJobProgressReads() = runBlocking {
        val hash = "a".repeat(40)
        val fresh = RemoteAddProgress(
            registered = true, metadataPending = false, started = true,
            traffic = RemoteTrafficSample(hash, 1L, 100L, 25L, 100L, true, 2L, 5L)
        )
        val events = mutableListOf<String>()
        var reads = 0
        pollRemoteAddReadOnlyProgress(
            maxObservations = 2, intervalMillis = 0L,
            read = {
                reads++
                if (reads == 1) throw IOException("transient read-only gateway failure")
                fresh
            },
            onFresh = { observed ->
                assertSame(fresh, observed)
                events += "fresh"
            },
            onReadError = { events += "unavailable" }
        )
        assertEquals(2, reads)
        assertEquals(listOf("unavailable", "fresh"), events)
        assertEquals(25L, fresh.payloadVerifiedBytes)
        assertEquals("Downloading payload from peers.", fresh.summary())
    }

    @Test fun transientHttp500StillAllowsFreshExactJobReadback() = runBlocking {
        val sample = RemoteAddProgress(registered = true, metadataPending = false, started = true)
        var attempts = 0
        var failures = 0
        var fresh = 0
        pollRemoteAddReadOnlyProgress(
            maxObservations = 2, intervalMillis = 0L,
            read = {
                attempts++
                if (attempts == 1) throw io.github.gamesbyian.chunks.rpc.RpcHttpStatusException(500)
                sample
            },
            onFresh = { if (it === sample) fresh++ },
            onReadError = { failures++ }
        )
        assertEquals(2, attempts)
        assertEquals(1, failures)
        assertEquals(1, fresh)
    }

    @Test fun authAndRateLimitResponsesStopWithoutRepeatingChallenge() = runBlocking {
        for (status in listOf(401, 403, 407, 429)) {
            var attempts = 0
            var failures = 0
            var fresh = 0
            pollRemoteAddReadOnlyProgress(
                maxObservations = 3, intervalMillis = 0L,
                read = {
                    attempts++
                    throw io.github.gamesbyian.chunks.rpc.RpcHttpStatusException(status)
                },
                onFresh = { fresh++ },
                onReadError = { failures++ }
            )
            assertEquals("HTTP $status must stop passive polling", 1, attempts)
            assertEquals(1, failures)
            assertEquals(0, fresh)
        }
    }

    @Test fun cancellationPropagatesAndDoesNotLookLikeReadFailure() {
        val cancel = CancellationException("screen left; old read must not continue")
        var failedReads = 0
        var observed = 0
        val thrown = runCatching {
            runBlocking {
                pollRemoteAddReadOnlyProgress(
                    maxObservations = 2, intervalMillis = 0L,
                    read = { throw cancel },
                    onFresh = { observed++ },
                    onReadError = { failedReads++ }
                )
            }
        }.exceptionOrNull()
        assertSame(cancel, thrown)
        assertEquals(0, failedReads)
        assertEquals(0, observed)
    }

    @Test fun persistentReadFailureIsBoundedAndNeverFabricatesProgress() = runBlocking {
        var reads = 0
        var failures = 0
        var successes = 0
        pollRemoteAddReadOnlyProgress(
            maxObservations = 3, intervalMillis = 0L,
            read = {
                reads++
                throw IOException("unavailable")
            },
            onFresh = { successes++ },
            onReadError = { failures++ }
        )
        assertEquals(3, reads)
        assertEquals(3, failures)
        assertEquals(0, successes)
        assertTrue(reads <= 3)
    }
}
