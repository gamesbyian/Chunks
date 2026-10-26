package io.github.gamesbyian.chunks

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

/**
 * Explicitly separate native status observations from lifecycle commands.
 *
 * A native poll may never return. Only one poll may run or be queued at a
 * time, and user Stop all / Android onTimeout must NOT queue behind it.
 * This is scheduler isolation, not a guarantee against a blocked JNI call,
 * libtorrent lock contention or the Android foreground-service time limit.
 */
internal class LocalServiceWorkLanes(
    private val pollExecutor: ExecutorService,
    private val controlExecutor: ExecutorService
) {
    private val pollGate = LocalServicePollGate()

    /** Skips overlapping observations without enqueuing unbounded work. */
    fun submitPoll(read: () -> Unit): Boolean {
        if (!pollGate.tryBegin()) return false
        return try {
            pollExecutor.execute {
                try {
                    read()
                } finally {
                    pollGate.finish()
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            pollGate.finish()
            false
        }
    }

    /** Null if idle; measured without waiting for a possibly stuck native read. */
    fun inFlightPollMillis(): Long? = pollGate.inFlightMillis()

    /** Separate FIFO lane for explicit user and OS lifecycle commands. */
    fun submitControl(command: () -> Unit): Boolean = try {
        controlExecutor.execute(command)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    fun shutdown() {
        pollExecutor.shutdown()
        controlExecutor.shutdown()
    }
}
