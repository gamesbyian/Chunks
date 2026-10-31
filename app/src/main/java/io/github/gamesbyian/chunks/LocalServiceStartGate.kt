package io.github.gamesbyian.chunks

/**
 * One-way dataSync foreground-service lifetime boundary.
 *
 * Android 15+ calls onTimeout after the app's dataSync foreground budget is
 * exhausted. A concurrent notification/user start request MUST NOT promote
 * the same service instance back to the foreground while its native Stop-all
 * and bounded timeout notice are still in flight. Those callbacks own the
 * retirement; a redundant start request must not call stopSelf early.
 *
 * Pure policy for deterministic testing; Android platform behavior still
 * requires the opt-in API35 shortened-limit instrumentation (#569).
 */
internal object LocalServiceStartGate {
    enum class Decision {
        PROMOTE,
        IGNORE_WHILE_TIMING_OUT,
        RETIRE_FINISHED
    }

    fun decide(finished: Boolean, timedOut: Boolean): Decision = when {
        finished -> Decision.RETIRE_FINISHED
        timedOut -> Decision.IGNORE_WHILE_TIMING_OUT
        else -> Decision.PROMOTE
    }
}
