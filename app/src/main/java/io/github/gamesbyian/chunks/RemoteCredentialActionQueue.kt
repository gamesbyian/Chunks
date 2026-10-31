package io.github.gamesbyian.chunks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Serializes user-requested remembered-login policy / Forget mutations in
 * the exact order the UI received them. Two rapid changes must not execute
 * in the opposite order merely because one IO dispatcher starts later.
 *
 * Never cancel an earlier opt-out when superseded: it must durably erase
 * previously saved credentials before a later opt-in can run.
 */
internal class RemoteCredentialActionQueue {
    private var lastJob: Job? = null
    private var revision = 0L

    fun submit(scope: CoroutineScope, action: suspend (revision: Long) -> Unit): Job {
        val predecessor = lastJob
        val ticket = ++revision
        val job = scope.launch {
            predecessor?.join()
            action(ticket)
        }
        lastJob = job
        return job
    }

    /** Snapshot UI action ordering before a suspending Connect or profile refresh. */
    fun snapshotRevision(): Long = revision

    fun isLatest(ticket: Long): Boolean = ticket == revision

    /**
     * A Connect initiated after a Forget or Remember toggle must see the
     * durable effect of that earlier user command before making a new vault
     * decision. Re-read lastJob after each suspension: another command may
     * have been queued while the previous one was running.
     */
    suspend fun awaitSettled() {
        while (true) {
            val pending = lastJob ?: return
            pending.join()
            if (lastJob === pending) return
        }
    }
}
