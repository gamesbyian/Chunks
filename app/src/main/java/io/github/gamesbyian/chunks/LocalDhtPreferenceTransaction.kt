package io.github.gamesbyian.chunks

/**
 * Do not persist a network-discovery preference as successfully applied until
 * the running engine has actually read back the requested DHT state.
 *
 * A preference-only change (no engine yet) does not start any network service.
 * Failures leave the prior saved choice intact; the UI separately reports the
 * possibility of uncertain live DHT state if a native change partially applied.
 */
internal object LocalDhtPreferenceTransaction {
    fun commitIfApplied(
        requested: Boolean,
        applyToRunningEngine: (() -> Boolean)?,
        savePreference: (Boolean) -> Boolean
    ): Boolean {
        if (applyToRunningEngine != null &&
            applyToRunningEngine.invoke() != requested) return false
        return savePreference(requested)
    }
}
