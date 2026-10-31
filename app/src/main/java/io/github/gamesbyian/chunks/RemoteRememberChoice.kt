package io.github.gamesbyian.chunks

/**
 * The "do not remember" choice is made when Connect starts, but the
 * authenticated network probe may finish after another Connect, Forget or
 * cancellation. A stale opt-out must not erase a newer account's ciphertext.
 *
 * Use the exact same process-wide lock as the credential vault operations,
 * so generation comparison and durable opt-out have no interleaving window.
 */
internal object RemoteRememberChoice {
    fun declineIfCurrent(vault: RememberedRemoteLogin, ticket: Long): Boolean =
        synchronized(RemoteCredentialLock.monitor) {
            if (!RemoteCredentialLock.current(ticket)) false
            else vault.setRememberingEnabled(false)
        }
}
