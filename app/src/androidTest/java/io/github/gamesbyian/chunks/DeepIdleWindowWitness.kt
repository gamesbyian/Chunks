package io.github.gamesbyian.chunks

/**
 * Android test-only evidence gate for opt-in forced-Doze observation.
 * Sampling never proves continuous idle between reads, but an observed
 * departure must never be reported as a successful sustained-Doze window.
 */
internal class DeepIdleWindowWitness {
    var samples: Int = 0
        private set
    var nonIdleSamples: Int = 0
        private set

    fun record(isIdle: Boolean) {
        samples++
        if (!isIdle) nonIdleSamples++
    }

    fun sustained(expectedSamples: Int): Boolean =
        expectedSamples > 0 && samples == expectedSamples && nonIdleSamples == 0
}
