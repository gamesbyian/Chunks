package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteLifecycleCancellationTest {
    @Test fun cancelledUiScopePropagatesWithoutAnImplicitRetry() = runBlocking {
        var calls = 0
        try {
            remoteLifecycleOutcomeOrUnknown {
                calls++
                throw CancellationException("disconnected")
            }
            fail("Cancellation must stop the obsolete UI coroutine")
        } catch (_: CancellationException) { }
        assertEquals(1, calls)
    }

    @Test fun failedRpcYieldsUnknownWithoutClaimingRefusalOrRetry() = runBlocking {
        var calls = 0
        val result = remoteLifecycleOutcomeOrUnknown {
            calls++
            throw IllegalStateException("ambiguous network response")
        }
        assertNull(result)
        assertEquals(1, calls)
    }

    @Test fun pendingAndVerifiedResultsAreNeverUpgradedOrChanged() = runBlocking {
        val verified = LifecycleOutcome.Verified(true)
        assertEquals(verified, remoteLifecycleOutcomeOrUnknown { verified })
    }
}
