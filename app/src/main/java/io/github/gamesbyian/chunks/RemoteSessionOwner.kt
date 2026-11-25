package io.github.gamesbyian.chunks

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteTransfer

/**
 * The one authenticated rTorrent session and its last accepted inventory,
 * retained through Activity recreation in RAM only (A2611-02). Process death
 * still drops it; the existing one-shot read-only startup reconnect applies.
 *
 * The request fences and the mutation readback gate belong to the SAME owner:
 * a retained session with a fresh gate would forget an uncertain write's
 * readback latch and admit another write without the fresh read it requires.
 * Coroutines (refresh, Connect, policy ticks) stay composition-scoped: a
 * recreation cancels them and the next fence ticket makes any late reply
 * non-current. No credential, private source or tracker data is kept here
 * beyond what [RTorrentRemote] already holds for the live session.
 */
internal class RemoteSessionOwner : ViewModel() {
    val server = mutableStateOf<RTorrentRemote?>(null)
    val version = mutableStateOf("")
    val transfers = mutableStateOf<List<RemoteTransfer>>(emptyList())
    /** Wall-clock time of the last accepted server list. */
    val transfersObservedAt = mutableStateOf<Long?>(null)
    /** Advances only when the server delivered a fresh, accepted queue read. */
    val snapshotRevision = mutableLongStateOf(0L)
    val stale = mutableStateOf(false)
    val aliasScope = mutableStateOf("")
    /** The view [transfers] was read from, and the view the person asked for. */
    val currentView = mutableStateOf("main")
    val requestedView = mutableStateOf("main")
    val remoteFence = RemoteUiRequestFence()
    val inspectionFence = RemoteUiRequestFence()
    val mutationGate = RemoteMutationReadbackGate()

    /**
     * Incremented when a mutation settles. A write that outlives the
     * composition which started it (the retained rTorrent Add) would otherwise
     * call that disposed composition's refresh, which never runs, leaving the
     * readback latch set; the live composition observes this and reads once.
     */
    val readbackRequests = mutableLongStateOf(0L)

    fun requestReadback() {
        readbackRequests.longValue++
    }
}
