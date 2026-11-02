package io.github.gamesbyian.chunks.rpc

/** Exact-job observations after Add. Placeholder bytes are never payload progress. */
data class RemoteAddProgress(
    val registered: Boolean,
    val metadataPending: Boolean? = null,
    val started: Boolean? = null,
    val traffic: RemoteTrafficSample? = null
) {
    val payloadSizeBytes: Long? get() = traffic?.expectedBytes?.takeIf { metadataPending == false }
    val payloadVerifiedBytes: Long? get() = traffic?.verifiedBytes?.takeIf { metadataPending == false }

    fun summary(): String = when {
        !registered -> "Waiting for the server to register this torrent. Do not submit it again."
        started == false -> if (metadataPending == true) "Stopped. Magnet metadata is still pending." else "Stopped."
        metadataPending == true -> if ((traffic?.peers ?: 0L) > 0L)
            "Fetching magnet metadata from peers." else "Fetching magnet metadata. Waiting for peers."
        metadataPending == null -> "Torrent registered. Metadata status is unavailable on this server."
        traffic?.expectedBytes != null && traffic.expectedBytes > 0L &&
            traffic.verifiedBytes == traffic.expectedBytes -> "Download complete. All payload pieces verified by rTorrent."
        traffic?.active == false -> "Metadata resolved. Waiting for rTorrent to activate the download."
        (traffic?.rateBytesPerSecond ?: 0L) > 0L && (traffic?.receivedBytes ?: 0L) > 0L ->
            "Downloading payload from peers."
        traffic?.peers == 0L -> "Metadata resolved. Waiting for peers."
        else -> "Metadata resolved. Waiting for payload traffic."
    }
}
