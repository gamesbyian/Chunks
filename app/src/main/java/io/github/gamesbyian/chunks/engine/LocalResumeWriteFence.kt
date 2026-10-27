package io.github.gamesbyian.chunks.engine

/**
 * Save-resume alerts are asynchronous. A callback may resolve a torrent hash
 * immediately before a session-only removal, then reach the actual disk write
 * after the resume file was deleted. Synchronize identity validation AND the
 * write with the same map lock used to remove the handle.
 *
 * The writer must be run while the map lock is held. Removing a handle must
 * acquire that lock before deleting its saved resume file, so a late alert
 * cannot quietly resurrect a removed torrent after the next app restart.
 */
internal object LocalResumeWriteFence {
    fun <H> writeIfCurrent(
        handles: Map<String, H>,
        key: String,
        originatingHandle: H,
        write: () -> Unit
    ): Boolean = synchronized(handles) {
        if (handles[key] != originatingHandle) false
        else {
            write()
            true
        }
    }
}
