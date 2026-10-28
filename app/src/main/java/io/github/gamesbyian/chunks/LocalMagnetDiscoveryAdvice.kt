package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader

/**
 * Advisory only. A magnet may include explicit peers, and an announced
 * tracker may be offline; neither case can be decided from link syntax.
 * This is not proof that discovery will succeed or fail.
 */
internal object LocalMagnetDiscoveryAdvice {
    private val ipv4Peer = Regex("""(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})""")
    private val ipv6Peer = Regex("""\[([0-9A-Fa-f:.]{2,45})]:(\d{1,5})""")

    fun warn(magnet: String, dhtPreferred: Boolean): Boolean =
        !dhtPreferred &&
            magnet.length <= 8192 &&
            magnet.startsWith("magnet:?", ignoreCase = true) &&
            TorrentMetainfoReader.magnetTrackerCount(magnet) == 0 &&
            directPeers(magnet).isEmpty()

    /**
     * Well-formed `x.pe` peer addresses (BEP 9), as libtorrent accepts them:
     * IP literal and port only, at most eight. A named peer is a route to
     * try, not proof that it is reachable or holds the torrent.
     */
    fun directPeers(magnet: String): List<String> {
        if (magnet.length > 8192 || !magnet.startsWith("magnet:?", ignoreCase = true)) return emptyList()
        return magnet.substringAfter('?').split('&').asSequence()
            .mapNotNull { part ->
                val pair = part.split('=', limit = 2)
                if (pair.size != 2 || !pair[0].equals("x.pe", ignoreCase = true)) return@mapNotNull null
                val value = runCatching { java.net.URLDecoder.decode(pair[1], "UTF-8") }.getOrNull()
                    ?: return@mapNotNull null
                val match = ipv4Peer.matchEntire(value) ?: ipv6Peer.matchEntire(value) ?: return@mapNotNull null
                val port = match.groupValues[2].toInt()
                val validHost = match.groupValues[1].let { host ->
                    if (value.startsWith("[")) host.contains(':')
                    else host.split('.').all { it.toInt() in 0..255 }
                }
                value.takeIf { validHost && port in 1..65535 }
            }
            .distinct().take(8).toList()
    }
}
