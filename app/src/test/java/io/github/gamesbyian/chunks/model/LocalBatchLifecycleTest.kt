package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalBatchLifecycleTest {
    private fun t(c: Char, stopped: Boolean) = LocalBatchLifecycle.Target(c.toString().repeat(40), stopped)

    @Test fun startsStoppedItemsAndSkipsRunningOnes() {
        val calls = ArrayList<String>()
        val report = LocalBatchLifecycle.run(listOf(t('a', true), t('b', false), t('c', true)), true) { h, s ->
            assertTrue(s); calls += h; LocalCommandOutcome.VERIFIED
        }
        assertEquals(listOf("a".repeat(40), "c".repeat(40)), calls)
        assertEquals(2, report.verified)
        assertEquals(1, report.skipped)
        assertNull(report.haltedOn)
    }

    @Test fun uncertainNotFoundOrFailureHaltWithoutRetry() {
        for (bad in listOf<() -> LocalCommandOutcome>({ LocalCommandOutcome.UNCERTAIN },
            { LocalCommandOutcome.NOT_FOUND }, { error("jni") })) {
            val calls = ArrayList<String>()
            val report = LocalBatchLifecycle.run(listOf(t('a', false), t('b', false), t('c', false)), false) { h, _ ->
                calls += h
                if (h.startsWith("b")) bad() else LocalCommandOutcome.VERIFIED
            }
            assertEquals(listOf("a".repeat(40), "b".repeat(40)), calls)
            assertEquals("b".repeat(40), report.haltedOn)
            assertEquals(1, report.notAttempted)
            assertTrue(LocalBatchLifecycle.describe(report).contains("Halted"))
        }
    }

    @Test fun invalidBatchesRunNothing() {
        for (bad in listOf(emptyList(), listOf(t('a', true), t('a', true)), listOf(t('z', true)))) {
            try {
                LocalBatchLifecycle.run(bad, true) { _, _ -> fail("ran"); LocalCommandOutcome.VERIFIED }
                fail("accepted")
            } catch (_: IllegalArgumentException) {}
        }
    }
}
