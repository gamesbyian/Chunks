package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalTransferStatus

/**
 * Foreground-service liveness decision for the last observed local engine state.
 * Null means a failed read, never an empty queue. An unavailable observation
 * must not terminate the service and interrupt torrents that may still run.
 */
internal sealed class LocalServicePoll {
    object Idle : LocalServicePoll()
    object Unavailable : LocalServicePoll()
    data class Active(val count: Int, val averagePercent: Long) : LocalServicePoll()

    companion object {
        fun classify(transfers: List<LocalTransferStatus>?): LocalServicePoll {
            if (transfers == null) return Unavailable
            val running = transfers.filterNot { it.stopped }
            if (running.isEmpty()) return Idle
            return Active(
                count = running.size,
                // A native status can be temporarily malformed during teardown or
                // recovery. Never expose impossible progress in the ongoing
                // notification; calculate with Long to avoid Int overflow.
                averagePercent = running.sumOf {
                    it.progressPpm.toLong().coerceIn(0L, 1_000_000L)
                } / running.size / 10_000
            )
        }
    }
}
