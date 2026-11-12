package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.RemoteTransfer

/**
 * One status vocabulary shared with the local device ("Downloading", "Sharing",
 * "Stopped", "Fetching metadata"), derived only from what rTorrent reports.
 * rTorrent's started flag is a requested state, so "Downloading" needs an
 * observed download rate from a fresh read; otherwise a started, incomplete
 * transfer is just "Started".
 */
internal object RemoteStatusLabel {
    data class Status(val label: String, val tone: ChunksTone)

    fun of(transfer: RemoteTransfer, stale: Boolean): Status {
        val complete = transfer.sizeBytes > 0 && transfer.completedBytes >= transfer.sizeBytes
        return when {
            transfer.metadataPending == true -> Status("Fetching metadata", ChunksTone.ACTIVE)
            !transfer.started -> Status("Stopped", ChunksTone.PAUSED)
            complete -> Status("Sharing", ChunksTone.COMPLETE)
            !stale && (transfer.downBytesPerSecond ?: 0L) > 0L -> Status("Downloading", ChunksTone.ACTIVE)
            else -> Status("Started", ChunksTone.ACTIVE)
        }
    }
}
