package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteBatchLifecycleTest {
    private fun hash(c: Char) = c.toString().repeat(40)
    private val a = RemoteBatchLifecycle.Target(hash('a'), started = false)
    private val b = RemoteBatchLifecycle.Target(hash('b'), started = true)
    private val c = RemoteBatchLifecycle.Target(hash('c'), started = false)
    private val d = RemoteBatchLifecycle.Target(hash('d'), started = false)

    @Test fun startsOnlyStoppedItemsInOrderAndReportsEachOutcome() = runBlocking {
        val calls = ArrayList<String>()
        val report = RemoteBatchLifecycle.run(listOf(a, b, c), true) { h, wanted ->
            calls += h
            assertTrue(wanted)
            if (h == hash('c')) LifecycleOutcome.Pending(true, true) else LifecycleOutcome.Verified(true)
        }
        assertEquals(listOf(hash('a'), hash('c')), calls)
        assertEquals(listOf(hash('a')), report.verified)
        assertEquals(listOf(hash('c')), report.pending)
        assertEquals(listOf(hash('b')), report.skipped)
        assertFalse(report.halted)
    }

    @Test fun uncertainOutcomeHaltsWithoutRetryAndLeavesTheRestUntouched() = runBlocking {
        val calls = ArrayList<String>()
        val report = RemoteBatchLifecycle.run(listOf(a, c, d), true) { h, _ ->
            calls += h
            if (h == hash('c')) LifecycleOutcome.Uncertain(true) else LifecycleOutcome.Verified(true)
        }
        assertEquals("No retry and nothing after the uncertain item", listOf(hash('a'), hash('c')), calls)
        assertEquals(hash('c'), report.haltedOn)
        assertTrue(report.haltedUncertain)
        assertEquals(listOf(hash('d')), report.notAttempted)
    }

    @Test fun unexpectedExceptionAfterSubmissionHaltsAsUncertainWithoutRetry() = runBlocking {
        val calls = mutableListOf<String>()
        val report = RemoteBatchLifecycle.run(listOf(a, c), true) { h, _ ->
            calls += h
            if (h == hash('a')) error("Reply parser failed after sending command")
            else LifecycleOutcome.Verified(true)
        }
        assertEquals(hash('a'), report.haltedOn)
        assertTrue(report.haltedUncertain)
        assertEquals(listOf(hash('c')), report.notAttempted)
        assertEquals(listOf(hash('a')), calls)
        assertTrue(report.verified.isEmpty())
    }

    @Test fun stopSkipsAlreadyStopped() = runBlocking {
        val report = RemoteBatchLifecycle.run(listOf(a, b), false) { _, _ -> LifecycleOutcome.Verified(false) }
        assertEquals(listOf(hash('b')), report.verified)
        assertEquals(listOf(hash('a')), report.skipped)
        assertNull(report.haltedOn)
    }

    @Test fun malformedDuplicateEmptyOrOversizedBatchesIssueNoCommand() = runBlocking {
        for (bad in listOf(emptyList(), listOf(a, a), listOf(a.copy(hash = "zz")),
            List(513) { RemoteBatchLifecycle.Target("%040x".format(it), false) })) {
            try {
                RemoteBatchLifecycle.run(bad, true) { _, _ -> fail("command issued"); error("") }
                fail("accepted $bad")
            } catch (_: IllegalArgumentException) {}
        }
    }
}
