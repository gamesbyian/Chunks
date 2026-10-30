package io.github.gamesbyian.chunks

/**
 * The OS timeout must end the foreground service even if its control worker is
 * stuck in JNI, waiting for the engine monitor, or behind an earlier Stop.
 *
 * All entry points and callbacks run on the service's main-thread Handler. This
 * deadline only bounds service termination; it cannot certify or cancel native
 * work. A late worker result must not replace an already published uncertain
 * result or finish a later service lifetime.
 */
internal class LocalServiceTimeoutStop(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val onFinished: (LocalServiceTimeoutNotice.PauseSummary) -> Unit,
    private val deadlineMs: Long = 4_000L
) {
    init { require(deadlineMs > 0L) }

    private enum class State { NOT_REQUESTED, WAITING, FINISHED }
    private var state = State.NOT_REQUESTED
    val requested: Boolean get() = state != State.NOT_REQUESTED

    private val deadline = Runnable {
        complete(LocalServiceTimeoutNotice.PauseSummary(listed = null, confirmed = 0))
    }

    fun begin(): Boolean {
        if (state != State.NOT_REQUESTED) return false
        state = State.WAITING
        postDelayed(deadline, deadlineMs)
        return true
    }

    fun complete(summary: LocalServiceTimeoutNotice.PauseSummary) {
        if (state != State.WAITING) return
        state = State.FINISHED
        removeCallbacks(deadline)
        onFinished(summary)
    }

    /** Destruction invalidates pending callbacks without inventing a result. */
    fun abandon() {
        state = State.FINISHED
        removeCallbacks(deadline)
    }
}
