package io.github.gamesbyian.chunks.rpc

/**
 * No tracker URL, announce response, passkey, peer IP or fault body leaves
 * rTorrent. Counts are historical *session* observations, not current peer
 * reachability and not proof the torrent has usable seeders.
 */
data class RemoteTrackerHealth(
    val total: Int,
    val enabled: Int,
    val successfulAnnounces: Long,
    val failedAnnounces: Long,
    /** Maximum peers returned by one tracker at its latest announce. */
    val maxAnnouncedPeers: Long?
) {
    init {
        require(total in 0..256 && enabled in 0..total)
        require(successfulAnnounces >= 0L && failedAnnounces >= 0L)
        require(maxAnnouncedPeers == null || maxAnnouncedPeers >= 0L)
    }

    fun summary(): String = when {
        total == 0 ->
            "No trackers are configured for this torrent. If rTorrent supports and enables DHT, discovery may still be possible."
        enabled == 0 ->
            "All $total configured trackers are disabled; tracker-based discovery will not run until enabled."
        successfulAnnounces == 0L && failedAnnounces > 0L ->
            "Tracker announces have failed without a recorded success in this session. Inspect the server's tracker errors and network access."
        successfulAnnounces == 0L ->
            "No successful tracker announce recorded in this session yet. Wait for scheduling or inspect tracker connectivity."
        maxAnnouncedPeers == 0L ->
            "At least one tracker announce succeeded, but no peers were reported by the latest tracker counters. This may reflect swarm availability or stale counters."
        maxAnnouncedPeers != null && maxAnnouncedPeers > 0L ->
            "A tracker previously reported peers; this does not prove that rTorrent connected to them or downloaded bytes."
        else ->
            "Tracker history is available, but recent peer counts are unsupported. Check the server's peer list and byte-progress observation."
    }
}
