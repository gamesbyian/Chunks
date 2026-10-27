package io.github.gamesbyian.chunks.rpc

/**
 * A conservative one-snapshot comparison, never evidence of payload bytes.
 * A remote queue may be a filtered rTorrent view; only report that SOME other
 * transfer in the loaded view had a positive *reported* download rate. Do
 * not claim global server availability or infer an unreported rate as zero.
 */
internal object RemoteQueueActivityContext {
    fun otherTransferReportsDownloading(
        target: RemoteTransfer,
        loadedView: List<RemoteTransfer>,
        snapshotFresh: Boolean
    ): Boolean {
        if (!snapshotFresh || !target.started || target.sizeBytes <= 0L ||
            target.completedBytes >= target.sizeBytes || target.downBytesPerSecond != 0L) {
            return false
        }
        return loadedView.any {
            !it.hash.equals(target.hash, ignoreCase = true) &&
                (it.downBytesPerSecond ?: 0L) > 0L
        }
    }
}
