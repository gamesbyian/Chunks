package io.github.gamesbyian.chunks

/**
 * Opt-in Android real-peer network-flap evidence gate. Radio setting changes
 * are not evidence that the application lost Internet access: an emulator may
 * still have validated Ethernet, and a handset may have a VPN/alternate route.
 *
 * Two consecutive samples are needed at each edge to reject transient reads.
 * The test's ConnectivityManager probe supplies actual app-visible state.
 */
internal class DefaultNetworkValidationWitness {
    enum class Phase { NEED_BASELINE, NEED_LOSS, NEED_RETURN, COMPLETE }

    var phase = Phase.NEED_BASELINE
        private set

    private var matchingSamples = 0

    fun observe(validatedDefaultInternet: Boolean): Phase {
        if (phase == Phase.COMPLETE) return phase
        val expected = phase != Phase.NEED_LOSS
        matchingSamples = if (validatedDefaultInternet == expected) matchingSamples + 1 else 0
        if (matchingSamples >= 2) {
            phase = when (phase) {
                Phase.NEED_BASELINE -> Phase.NEED_LOSS
                Phase.NEED_LOSS -> Phase.NEED_RETURN
                Phase.NEED_RETURN -> Phase.COMPLETE
                Phase.COMPLETE -> Phase.COMPLETE
            }
            matchingSamples = 0
        }
        return phase
    }
}
