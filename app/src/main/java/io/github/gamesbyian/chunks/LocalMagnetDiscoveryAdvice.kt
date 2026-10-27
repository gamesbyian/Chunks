package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader

/**
 * Advisory only. A magnet may include explicit peers, and an announced
 * tracker may be offline; neither case can be decided from link syntax.
 * This is not proof that discovery will succeed or fail.
 */
internal object LocalMagnetDiscoveryAdvice {
    fun warn(magnet: String, dhtPreferred: Boolean): Boolean =
        !dhtPreferred &&
            magnet.length <= 8192 &&
            magnet.startsWith("magnet:?", ignoreCase = true) &&
            TorrentMetainfoReader.magnetTrackerCount(magnet) == 0
}
