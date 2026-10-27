package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDhtPreferenceTransactionTest {
    @Test fun noRunningEnginePersistsFuturePreferenceWithoutStartingNetwork() {
        val saved = mutableListOf<Boolean>()
        assertTrue(LocalDhtPreferenceTransaction.commitIfApplied(
            requested = true, applyToRunningEngine = null,
            savePreference = { saved += it; true }))
        assertEquals(listOf(true), saved)
    }

    @Test fun failedNativeReadbackNeverPersistsMisleadingDhtChoice() {
        var persisted = false
        assertFalse(LocalDhtPreferenceTransaction.commitIfApplied(
            requested = true,
            applyToRunningEngine = { false },
            savePreference = { persisted = true; true }))
        assertFalse(persisted)
    }

    @Test fun nativeStopMustBeConfirmedBeforeRecordingDhtDisabled() {
        var persisted = false
        assertFalse(LocalDhtPreferenceTransaction.commitIfApplied(
            requested = false,
            applyToRunningEngine = { true },
            savePreference = { persisted = true; true }))
        assertFalse(persisted)
    }

    @Test fun successfulNativeReadbackPrecedesPreferenceCommit() {
        val calls = mutableListOf<String>()
        assertTrue(LocalDhtPreferenceTransaction.commitIfApplied(
            requested = false,
            applyToRunningEngine = { calls += "native"; false },
            savePreference = { calls += "persist"; true }))
        assertEquals(listOf("native", "persist"), calls)
    }

    @Test fun failedPersistenceMustNotClaimAConfirmedChoice() {
        assertFalse(LocalDhtPreferenceTransaction.commitIfApplied(
            requested = true,
            applyToRunningEngine = { true },
            savePreference = { false }))
    }

    @Test fun nativeExceptionDoesNotPersistThePreference() {
        var persisted = false
        try {
            LocalDhtPreferenceTransaction.commitIfApplied(
                requested = true,
                applyToRunningEngine = { throw IllegalStateException("Native read unavailable") },
                savePreference = { persisted = true; true })
            org.junit.Assert.fail("Native failure must propagate for UI uncertainty")
        } catch (_: IllegalStateException) { }
        assertFalse(persisted)
    }
}
