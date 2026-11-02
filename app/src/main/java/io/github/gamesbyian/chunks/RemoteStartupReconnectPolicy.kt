package io.github.gamesbyian.chunks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Cold-launch read-only reconnection is an opt-in consequence of saved verified
 * login state. An explicit Disconnect or a failed authentication must not turn
 * into an unbounded automatic retry. The actual connection uses the existing
 * authenticated RemoteSmartConnect path and remains mutation-free.
 */
internal object RemoteStartupReconnectPolicy {
    fun shouldAttempt(
        rememberingEnabled: Boolean,
        hasSavedVerifiedLogin: Boolean,
        alreadyAttempted: Boolean
    ): Boolean = rememberingEnabled && hasSavedVerifiedLogin && !alreadyAttempted
}

/**
 * Runs only after Compose has entered the Connection screen, never during
 * composition. The host consumes the token before invoking the normal
 * authenticated read-only Connect action to avoid implicit retry storms.
 */
@Composable
internal fun StartupRemoteReconnectEffect(
    eligible: Boolean,
    onConsumed: () -> Unit,
    onConnect: () -> Unit
) {
    LaunchedEffect(eligible) {
        if (eligible) {
            onConsumed()
            onConnect()
        }
    }
}
