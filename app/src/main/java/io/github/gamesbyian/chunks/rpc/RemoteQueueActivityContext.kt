package io.github.gamesbyian.chunks.rpc

import java.util.Locale

/**
 * A conservative one-snapshot comparison, never evidence of payload bytes.
 * A remote queue may be a filtered rTorrent view. Cache positive-rate hashes
 * ONCE per snapshot, not for each LazyColumn card (up to 12k transfers).
 * Unique exact-hash membership also refuses to attribute a duplicated
 * identity's positive rate to a target claiming a zero rate.
 */
internal object RemoteQueueActivityContext {
    fun hashesReportingDownloadTraffic(loadedView: List<RemoteTransfer>): Set<String> =
        loadedView.asSequence()
            .filter { (it.downBytesPerSecond ?: 0L) > 0L }
            .map { it.hash.uppercase(Locale.ROOT) }
            .toSet()

    fun otherTransferReportsDownloading(
        target: RemoteTransfer,
        positiveRateHashes: Set<String>,
        snapshotFresh: Boolean
    ): Boolean {
        if (!snapshotFresh || !target.started || target.sizeBytes <= 0L ||
            target.completedBytes >= target.sizeBytes || target.downBytesPerSecond != 0L) return false
        val sameHashPresent = target.hash.uppercase(Locale.ROOT) in positiveRateHashes
        return positiveRateHashes.size > if (sameHashPresent) 1 else 0
    }
}
