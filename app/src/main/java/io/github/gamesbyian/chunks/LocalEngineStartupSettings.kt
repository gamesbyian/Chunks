package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalRateLimits

/**
 * An engine is not publishable until both saved network preferences have been
 * applied and read back. In particular, a saved "DHT off" state must be
 * confirmed: merely having a preference does not establish current exposure.
 *
 * If setup throws or returns contradictory state, try to close the candidate
 * before the holder can publish it. Cleanup is best effort because a native
 * call can itself fail/hang. A partially initialized session is never returned
 * as a successful engine.
 */
internal object LocalEngineStartupSettings {
    fun <T> open(
        candidate: T,
        savedDht: Boolean,
        savedLimits: LocalRateLimits,
        start: (T) -> Unit,
        setDht: (T, Boolean) -> Boolean,
        setLimits: (T, LocalRateLimits) -> LocalRateLimits,
        close: (T) -> Unit
    ): T {
        try {
            start(candidate)
            check(setDht(candidate, savedDht) == savedDht) {
                "Saved DHT setting could not be verified on the local engine"
            }
            check(setLimits(candidate, savedLimits) == savedLimits) {
                "Saved speed limits could not be verified on the local engine"
            }
            return candidate
        } catch (failure: Exception) {
            try { close(candidate) } catch (_: Exception) { }
            throw failure
        }
    }
}
