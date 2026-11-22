package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.DelugeReadOnlyRpc
import io.github.gamesbyian.chunks.rpc.QBittorrentReadOnlyRpc
import io.github.gamesbyian.chunks.rpc.TransmissionReadOnlyRpc

/**
 * Protocol DTOs into the ONE existing Chunks transfers-list model.
 *
 * These conversions intentionally provide observations ONLY, without
 * EnvironmentAvailability, trusted timestamps or mutation capabilities.
 * An authenticated, exact-client, complete inventory must be established
 * separately before a caller may construct a LIVE EnvironmentTransferSnapshot.
 * Never use an infohash by itself as a global command address.
 */
internal object RemoteProtocolObservations {
    fun transmission(
        environment: EnvironmentId,
        snapshot: TransmissionReadOnlyRpc.Snapshot
    ): List<UnifiedTransferObservation> = checked(environment, snapshot.torrents.map { transfer ->
        UnifiedTransferObservation(
            key = UnifiedTransferKey.of(environment, transfer.hash),
            displayName = transfer.name,
            originalName = transfer.name,
            sizeBytes = transfer.totalBytes.takeIf { it > 0 },
            progressPpm = progress(transfer.totalBytes, transfer.completedBytes),
            started = when (transfer.nativeStatus) {
                0 -> false // TR_STATUS_STOPPED
                4, 6 -> true // active download or seed only; queued/checking unknown
                else -> null
            },
            downloadBytesPerSecond = transfer.downloadBytesPerSecond,
            uploadBytesPerSecond = transfer.uploadBytesPerSecond,
            peers = null,
            metadataPending = false.takeIf { transfer.totalBytes > 0 },
            // The server's directory is presentation evidence, never Place
            // authorization or proof that any path exists on this device.
            reportedSaveFolder = transfer.reportedDirectory.takeIf { it.isNotBlank() }
        )
    })

    fun qbittorrent(
        environment: EnvironmentId,
        snapshot: QBittorrentReadOnlyRpc.Snapshot
    ): List<UnifiedTransferObservation> = checked(environment, snapshot.torrents.map { transfer ->
        UnifiedTransferObservation(
            key = UnifiedTransferKey.of(environment, transfer.hash),
            displayName = transfer.name,
            originalName = transfer.name,
            sizeBytes = transfer.selectedSizeBytes.takeIf { it > 0 },
            // QBT reports selected size and downloaded bytes independently.
            // An overrun must remain unknown rather than become "100%".
            progressPpm = progress(transfer.selectedSizeBytes, transfer.completedBytes),
            started = when (transfer.state) {
                "downloading", "uploading", "stalledDL", "stalledUP",
                "forcedDL", "forcedUP" -> true
                "pausedDL", "pausedUP", "stoppedDL", "stoppedUP" -> false
                else -> null
            },
            downloadBytesPerSecond = transfer.downloadBytesPerSecond,
            uploadBytesPerSecond = transfer.uploadBytesPerSecond,
            peers = null,
            hasError = transfer.state in setOf("error", "missingFiles"),
            metadataPending = when {
                transfer.state in setOf("metaDL", "forcedMetaDL") -> true
                (transfer.totalSizeBytes ?: 0) > 0 -> false
                else -> null
            },
            reportedSaveFolder = transfer.reportedSavePath?.takeIf { it.isNotBlank() }
        )
    })

    fun deluge(
        environment: EnvironmentId,
        snapshot: DelugeReadOnlyRpc.Snapshot
    ): List<UnifiedTransferObservation> = checked(environment, snapshot.torrents.map { transfer ->
        UnifiedTransferObservation(
            key = UnifiedTransferKey.of(environment, transfer.hash),
            displayName = transfer.name,
            originalName = transfer.name,
            sizeBytes = transfer.totalSizeBytes?.takeIf { it > 0 },
            progressPpm = progress(transfer.totalSizeBytes, transfer.completedBytes),
            started = when (transfer.state) {
                "Downloading", "Seeding" -> true
                "Paused" -> false
                else -> null
            },
            downloadBytesPerSecond = transfer.downloadBytesPerSecond,
            uploadBytesPerSecond = transfer.uploadBytesPerSecond,
            peers = null,
            hasError = transfer.state == "Error",
            metadataPending = false.takeIf { (transfer.totalSizeBytes ?: 0) > 0 },
            reportedSaveFolder = transfer.reportedSavePath?.takeIf { it.isNotBlank() }
        )
    })

    /** Preserve exact client's identity and reject ambiguous constructed DTOs. */
    private fun checked(
        environment: EnvironmentId,
        rows: List<UnifiedTransferObservation>
    ): List<UnifiedTransferObservation> {
        require(environment != EnvironmentSnapshots.LOCAL) {
            "A remote protocol record cannot belong to the local engine"
        }
        require(rows.map { it.key }.toSet().size == rows.size) {
            "Duplicate same-environment torrent identity is ambiguous"
        }
        return rows
    }

    private fun progress(total: Long?, completed: Long?): Int? {
        if (total == null || completed == null || total <= 0 ||
            completed < 0 || completed > total) return null
        return (completed.toDouble() / total.toDouble() * 1_000_000.0)
            .toInt().coerceIn(0, 1_000_000)
    }
}
