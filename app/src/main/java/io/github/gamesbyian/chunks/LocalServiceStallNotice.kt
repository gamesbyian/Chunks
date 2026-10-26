package io.github.gamesbyian.chunks

/**
 * One informative foreground-notification update per stalled observation.
 * This is a visibility warning, never an instruction to stop, restart or
 * retry a torrent. Reset only when a fresh poll has been admitted.
 */
internal class LocalServiceStallNotice(private val warnAfterMillis: Long = 15_000L) {
    init { require(warnAfterMillis > 0L) }
    private var shownForCurrentPoll = false

    fun newPollAdmitted() {
        shownForCurrentPoll = false
    }

    fun shouldWarn(inFlightMillis: Long?): Boolean {
        if (inFlightMillis == null || inFlightMillis < warnAfterMillis || shownForCurrentPoll) return false
        shownForCurrentPoll = true
        return true
    }
}
