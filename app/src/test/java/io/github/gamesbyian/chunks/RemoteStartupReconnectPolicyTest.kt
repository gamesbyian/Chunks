package io.github.gamesbyian.chunks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteStartupReconnectPolicyTest {
    @Test fun lastVerifiedSavedLoginCanMakeOneInitialReadOnlyAttempt() {
        assertTrue(RemoteStartupReconnectPolicy.shouldAttempt(
            rememberingEnabled = true,
            hasSavedVerifiedLogin = true,
            alreadyAttempted = false
        ))
        assertFalse(RemoteStartupReconnectPolicy.shouldAttempt(
            rememberingEnabled = true,
            hasSavedVerifiedLogin = true,
            alreadyAttempted = true
        ))
    }

    @Test fun rememberLoginOptOutBlocksAutomaticConnection() {
        assertFalse(RemoteStartupReconnectPolicy.shouldAttempt(
            rememberingEnabled = false,
            hasSavedVerifiedLogin = true,
            alreadyAttempted = false
        ))
    }

    @Test fun noVerifiedSavedLoginNeverInitiatesRemoteDiscovery() {
        assertFalse(RemoteStartupReconnectPolicy.shouldAttempt(
            rememberingEnabled = true,
            hasSavedVerifiedLogin = false,
            alreadyAttempted = false
        ))
    }

    @Test fun failedConnectionCannotTriggerAnotherAutomaticAttempt() {
        // The Compose host consumes the one-shot token before calling the
        // existing Connect callback, even if it then fails with 401/timeout.
        val didConsumeBeforeRequest = true
        assertFalse(RemoteStartupReconnectPolicy.shouldAttempt(
            rememberingEnabled = true,
            hasSavedVerifiedLogin = true,
            alreadyAttempted = didConsumeBeforeRequest
        ))
    }
}
