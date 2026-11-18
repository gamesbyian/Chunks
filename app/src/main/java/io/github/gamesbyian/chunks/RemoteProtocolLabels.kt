package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.RemoteBackendKind

internal object RemoteProtocolLabels {
    fun kind(kind: RemoteBackendKind): String = when (kind) {
        RemoteBackendKind.RTORRENT -> "rTorrent"
        RemoteBackendKind.TRANSMISSION -> "Transmission"
        RemoteBackendKind.QBITTORRENT -> "qBittorrent"
        RemoteBackendKind.DELUGE -> "Deluge"
    }
}
