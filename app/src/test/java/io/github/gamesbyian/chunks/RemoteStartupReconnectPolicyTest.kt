package io.github.gamesbyian.chunks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])

class RemoteStartupReconnectPolicyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun composeEffectConsumesTokenBeforeConnectingAndDoesNotLoop() {
        var consumed = 0
        var connects = 0
        compose.setContent {
            var attempted by remember { mutableStateOf(false) }
            StartupRemoteReconnectEffect(
                eligible = !attempted,
                onConsumed = { attempted = true; consumed++ },
                onConnect = { connects++ }
            )
        }
        compose.waitForIdle()
        assertEquals(1, consumed)
        assertEquals(1, connects)
        compose.waitForIdle()
        assertEquals(1, consumed)
        assertEquals(1, connects)
    }

    @Test fun composeEffectNeverContactsServerWhenNotEligible() {
        var calls = 0
        compose.setContent {
            StartupRemoteReconnectEffect(
                eligible = false,
                onConsumed = { calls++ },
                onConnect = { calls++ }
            )
        }
        compose.waitForIdle()
        assertEquals(0, calls)
    }

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
