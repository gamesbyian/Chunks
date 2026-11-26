package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import kotlinx.coroutines.CancellationException

/**
 * The live composition as the inventory reader sees it (A2611-02). Members read or write
 * CURRENT state at the moment they are called; the reader never holds a captured copy.
 */
internal interface RemoteInventoryHost {
    val currentServer: RTorrentRemote?
    val viewToRead: String
    var isLoading: Boolean
    /** Forget the previous failure before a read starts. */
    fun clearError()
    /** A fresh, gate-accepted queue read replaced the list; stale details are discarded. */
    fun accept(snapshot: List<RemoteTransfer>, view: String)
    /** A read that cannot clear the safety latch, or failed: the list is stale. */
    fun markStale()
    fun failed(failure: Exception)
}

/**
 * One refresh: reads the queue for the requested view, repeating while another read was queued
 * behind it, exactly as the loop that lived in `ChunksApp.refresh` did. A response counts only
 * for the live session ([RemoteInventoryHost.currentServer] identity), the current request
 * generation ([fence]) and, for clearing an uncertain write's latch, a read that began after the
 * write settled ([gate]). A cancelled read rethrows before touching any state: recreation
 * cancels it, the session (and its stale flag) is retained, and the live composition decides.
 */
internal suspend fun readRemoteInventory(
    host: RemoteInventoryHost,
    remote: RTorrentRemote,
    fence: RemoteUiRequestFence,
    gate: RemoteMutationReadbackGate
) {
    var again: Boolean
    do {
        val ticket = fence.begin(host.viewToRead)
        val readbackTicket = gate.ticket()
        if (host.currentServer === remote) host.clearError()
        try {
            val snapshot = remote.listTransfersWithMetadata(ticket.view)
            if (host.currentServer === remote && fence.current(ticket) &&
                gate.acceptFreshRead(readbackTicket)) {
                host.accept(snapshot, ticket.view)
            } else if (host.currentServer === remote && fence.current(ticket)) {
                // A response from before/during an uncertain write
                // cannot clear its safety latch or replace the queue.
                host.markStale()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            if (host.currentServer === remote && fence.current(ticket)) host.failed(failure)
        }
        again = host.currentServer === remote && fence.current(ticket) && fence.consumeQueuedRefresh()
    } while (again)
    if (host.currentServer === remote) host.isLoading = false
}
