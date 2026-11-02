package io.github.gamesbyian.chunks

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
