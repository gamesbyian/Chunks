package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalRateLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalRateLimitPreferenceTransactionTest {
    private val requested = LocalRateLimits(128 * 1024, 64 * 1024)
    private val previous = LocalRateLimits(16 * 1024, 32 * 1024)

    @Test fun absentEngineCanSavePreferenceWithoutStartingNativeSession() {
        val calls = mutableListOf<String>()
        var saved = previous
        assertTrue(LocalRateLimitPreferenceTransaction.commitIfApplied(
            requested,
            applyToRunningEngine = null,
            savePreference = { calls += "save"; saved = it; true }
        ))
        assertEquals(listOf("save"), calls)
        assertEquals(requested, saved)
    }

    @Test fun readbackMismatchLeavesPreviousPreferenceUnchanged() {
        val calls = mutableListOf<String>()
        var saved = previous
        assertFalse(LocalRateLimitPreferenceTransaction.commitIfApplied(
            requested,
            applyToRunningEngine = { calls += "native"; previous },
            savePreference = { calls += "save"; saved = it; true }
        ))
        assertEquals(listOf("native"), calls)
        assertEquals(previous, saved)
    }

    @Test fun nativeExceptionNeverRecordsUnverifiedLimits() {
        var saveCalls = 0
        try {
            LocalRateLimitPreferenceTransaction.commitIfApplied(
                requested,
                applyToRunningEngine = { throw IllegalStateException("libtorrent unavailable") },
                savePreference = { saveCalls++; true }
            )
            fail("A native exception must not be swallowed or committed")
        } catch (_: IllegalStateException) { }
        assertEquals(0, saveCalls)
    }

    @Test fun exactReadbackMustPrecedeDurableSave() {
        val calls = mutableListOf<String>()
        assertTrue(LocalRateLimitPreferenceTransaction.commitIfApplied(
            requested,
            applyToRunningEngine = { calls += "native"; requested },
            savePreference = { calls += "save"; true }
        ))
        assertEquals(listOf("native", "save"), calls)
    }

    @Test fun failedPreferenceCommitDoesNotClaimSuccess() {
        assertFalse(LocalRateLimitPreferenceTransaction.commitIfApplied(
            requested,
            applyToRunningEngine = { requested },
            savePreference = { false }
        ))
    }

    @Test fun bothDirectionsMustMatchAndUnlimitedRemainsAnExplicitValue() {
        val unlimited = LocalRateLimits(0, 0)
        val asymmetric = LocalRateLimits(0, 12 * 1024)
        var saveCalls = 0
        assertFalse(LocalRateLimitPreferenceTransaction.commitIfApplied(
            unlimited, applyToRunningEngine = { asymmetric },
            savePreference = { saveCalls++; true }
        ))
        assertEquals(0, saveCalls)
        assertTrue(LocalRateLimitPreferenceTransaction.commitIfApplied(
            unlimited, applyToRunningEngine = { unlimited },
            savePreference = { saveCalls++; true }
        ))
        assertEquals(1, saveCalls)
    }
}
