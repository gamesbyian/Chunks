package io.github.gamesbyian.chunks.rpc

/**
 * A read-only point-in-time rTorrent diagnostic. The requested d.state is
 * not evidence of peer connection or downloaded bytes.
 * Nullable fields distinguish missing capabilities from false/zero.
 * Never include tracker URLs, RPC fault bodies or torrent paths in messages.
 */
data class RemoteTransferActivity(
    val active: Boolean?,
    val open: Boolean?,
    val connectedPeers: Long?,
    val downloadBytesPerSecond: Long?
) {
    fun summary(): String = when {
        active == false -> "rTorrent is not active. The Start state may be set, but the engine has not activated this torrent. Check server errors, hashing, queue limits or storage."
        open == false -> "rTorrent has not opened this torrent. Check storage path, filesystem permissions and disk space."
        active == true && connectedPeers == 0L ->
            "Engine active, 0 peers connected. Check trackers, DHT/peer discovery and whether live seeds exist."
        active == true && downloadBytesPerSecond == 0L ->
            "Engine active, no download traffic at this instant. Check peer availability and tracker status."
        active == true && (downloadBytesPerSecond ?: 0L) > 0 ->
            "Engine active and reporting download traffic. File completion still needs observation."
        active == true -> "Engine active. Peer and traffic details are not all available."
        else -> "This server did not expose enough activity diagnostics. Inspect its tracker/error view."
    }
}
