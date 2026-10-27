package io.github.gamesbyian.chunks

/**
 * A remote command must be followed by a fresh read that STARTED after the
 * command settled. A read already in flight before or during a write cannot
 * authorize further writes, even if its response happens to arrive later.
 *
 * Only the Compose UI dispatcher owns this gate. It does not replace the
 * adapter's exact-hash preflight and server-side verification.
 */
internal class RemoteMutationReadbackGate {
    private var generation = 0L
    private var commandPending = false
    private var awaitingReadback = false

    fun tryStart(): Boolean {
        if (commandPending || awaitingReadback) return false
        commandPending = true
        awaitingReadback = true
        ++generation
        return true
    }

    fun settled() {
        if (commandPending) {
            commandPending = false
            ++generation
        }
    }

    fun ticket(): Long = generation

    fun accepts(ticket: Long): Boolean = !commandPending && ticket == generation

    /** A failed/old read never releases the write lockout. */
    fun acceptFreshRead(ticket: Long): Boolean {
        if (!accepts(ticket)) return false
        awaitingReadback = false
        return true
    }

    fun reset() {
        commandPending = false
        awaitingReadback = false
        ++generation
    }
}
