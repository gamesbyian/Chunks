package io.github.gamesbyian.chunks.rpc

/**
 * A conservative one-snapshot comparison, never evidence of payload bytes.
 * A remote queue may be a filtered rTorrent view. Count positive rate entries
 * ONCE per snapshot, not in every LazyColumn card (up to 12k transfers).
 * With a known-zero target rate, any positive entry necessarily belongs to
 * a different infohash. Unknown rates are not evidence.
 */
internal object RemoteQueueActivityContext {
    fun countReportingDownloadTraffic(loadedView: List<RemoteTransfer>): Int =
        loadedView.count { (it.downBytesPerSecond ?: 0L) > 0L }

    fun otherTransferReportsDownloading(
        target: RemoteTransfer,
        positiveRateTransferCount: Int,
        snapshotFresh: Boolean
    ): Boolean {
        return snapshotFresh && target.started &&
            target.sizeBytes > 0L && target.completedBytes < target.sizeBytes &&
            target.downBytesPerSecond == 0L && positiveRateTransferCount > 0
    }
}
