package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAddAvailabilityTest {
    private fun enabled(
        plan: Boolean = true, submitting: Boolean = false, choosing: Boolean = false, reading: Boolean = false,
        noFile: Boolean = false, ready: Boolean = true, alias: Boolean = true
    ) = RemoteAddAvailability.enabled(plan, submitting, choosing, reading, noFile, ready, alias)

    @Test fun enabledOnlyWhenEveryConditionHolds() {
        assertTrue(enabled())
        assertFalse("no reviewable plan", enabled(plan = false))
        assertFalse("a submission is in flight (no double submit)", enabled(submitting = true))
        assertFalse("a file choice is open", enabled(choosing = true))
        assertFalse("an external document is being read", enabled(reading = true))
        assertFalse("every file is skipped", enabled(noFile = true))
        assertFalse("no fresh confirmed server list", enabled(ready = false))
        assertFalse("invalid display name", enabled(alias = false))
    }

    @Test fun theReasonNamesTheFirstProblem() {
        assertEquals("Review the source.", RemoteAddAvailability.unavailableReason(false, false, "Review the source."))
        assertEquals("Waiting for a fresh confirmed server transfer list.",
            RemoteAddAvailability.unavailableReason(true, false, "ignored"))
        assertNull(RemoteAddAvailability.unavailableReason(true, true, "ignored"))
    }
}
