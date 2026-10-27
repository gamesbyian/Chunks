package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalRateLimits

/**
 * A saved phone speed-limit preference must not claim a native change was
 * applied when libtorrent did not read back the requested values.
 *
 * When no session exists, saving future start-up preferences is permitted and
 * must not start a network session. A native exception or mismatched readback
 * leaves existing preferences untouched. If a subsequent storage commit fails,
 * the live engine may already have changed, so callers report uncertainty
 * rather than silently retrying an asynchronous native mutation.
 */
internal object LocalRateLimitPreferenceTransaction {
    fun commitIfApplied(
        requested: LocalRateLimits,
        applyToRunningEngine: (() -> LocalRateLimits)?,
        savePreference: (LocalRateLimits) -> Boolean
    ): Boolean {
        if (applyToRunningEngine != null &&
            applyToRunningEngine.invoke() != requested) return false
        return savePreference(requested)
    }
}
