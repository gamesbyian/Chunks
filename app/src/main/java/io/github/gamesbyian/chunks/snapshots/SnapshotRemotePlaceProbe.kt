package io.github.gamesbyian.chunks.snapshots

import io.github.gamesbyian.chunks.rpc.RemoteDirectoryLookup
import io.github.gamesbyian.chunks.rpc.RuTorrentDirectoryListing

/**
 * Read-only, exact-tenant folder proof for one user-selected stopped restore Place.
 *
 * A manually entered path, cached crumb or unverified default must not grant a
 * non-idempotent load.raw_verbose to that Place. Only the current authenticated
 * ruTorrent mount may supply both a root and an exact directory observation.
 * This does not create, move or delete remote files.
 */
internal object SnapshotRemotePlaceProbe {
    suspend fun confirm(
        selected: String,
        read: suspend (String) -> RemoteDirectoryLookup
    ): Boolean {
        if (selected == "/" || selected.length > 1024 ||
            RuTorrentDirectoryListing.canonical(selected) != selected) return false
        val root = (read("") as? RemoteDirectoryLookup.Ready)?.page?.path ?: return false
        if (!RuTorrentDirectoryListing.contains(root, selected)) return false
        val observed = (read(selected) as? RemoteDirectoryLookup.Ready)?.page?.path
        return observed == selected
    }
}
