package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.RemotePeerSource
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Copy a completed server transfer's actual bytes onto the phone, with the
 * rTorrent server as a direct BitTorrent peer (magnet `x.pe`, BEP 9).
 *
 * This is a real payload copy, unlike [TorrentIdentityHandoff] alone: the
 * phone engine fetches the metadata and every piece from the server and
 * verifies each piece against the torrent's own hashes. The server transfer
 * is never changed or removed (Copy, not Move), and other public peers may
 * also contribute pieces. Building the plan only reads; the phone Add still
 * goes through its own review and explicit confirmation.
 *
 * libtorrent accepts `x.pe` only as an IP literal, so the host is resolved
 * here and the chosen address is shown to the user before anything happens.
 */
internal object ServerToPhoneCopy {
    sealed interface Plan
    data class Ready(val magnet: String, val peer: String, val bytes: Long) : Plan
    data class Refused(val reason: String) : Plan

    fun plan(
        transfer: RemoteTransfer,
        source: RemotePeerSource,
        resolve: (String) -> List<InetAddress>
    ): Plan {
        val identity = TorrentIdentityHandoff.prepare(transfer.hash, transfer.name)
            ?: return Refused("Only transfers with a v1 info hash can be copied to the phone.")
        if (transfer.sizeBytes <= 0 || transfer.completedBytes != transfer.sizeBytes)
            return Refused("The server has ${(transfer.fractionComplete * 100).toInt()}% of this transfer. " +
                "It can only send what it holds, so finish the download on the server first.")
        if (source.isPrivate)
            return Refused("This is a private torrent. rTorrent does not share private torrents' metadata " +
                "this way, and their trackers' rules may forbid it.")
        if (!source.isActive)
            return Refused("The server is not running this torrent right now, so it cannot send it. " +
                "Start it on the server, then try again.")
        if (source.listenPort !in 1L..65535L)
            return Refused("The server did not report a usable BitTorrent listening port.")
        val host = source.announcedAddress ?: source.rpcHost
        // A loopback peer is only meaningful for the loopback development rig.
        val loopbackServer = source.rpcHost in setOf("localhost", "127.0.0.1", "::1")
        val resolved = try { resolve(host) } catch (_: Exception) { emptyList() }
        val address = resolved
            .filterNot { it.isAnyLocalAddress || it.isMulticastAddress || (it.isLoopbackAddress && !loopbackServer) }
            .let { all -> all.firstOrNull { it is Inet4Address } ?: all.firstOrNull { it is Inet6Address } }
            ?: return Refused("Chunks could not find a usable network address for the server ($host).")
        val literal = address.hostAddress.substringBefore('%')
        val peer = (if (address is Inet6Address) "[$literal]" else literal) + ":" + source.listenPort
        return Ready("$identity&x.pe=$peer", peer, transfer.sizeBytes)
    }
}
