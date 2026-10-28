package io.github.gamesbyian.chunks.rpc

import java.net.URI

/** Convert a user-entered ruTorrent web URL into a candidate HTTP-RPC URL. */
object RemoteEndpointHints {
    private val rutorrentRoot = Regex("(?i)/rutorrent[0-9]{0,3}$")

    fun rpcAddress(input: String): String {
        val uri = URI(input.trim())
        require(uri.scheme.equals("https", true) ||
            (uri.scheme.equals("http", true) &&
              uri.host in listOf("localhost", "127.0.0.1", "[::1]"))) {
            "Use HTTPS for remote ruTorrent"
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null) { "Invalid ruTorrent address" }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        // Hosts serve additional instances as /rutorrent2/, /rutorrent3/, ...
        val mapped = if (rutorrentRoot.containsMatchIn(path))
            path + "/plugins/httprpc/action.php" else path.ifEmpty { "/" }
        // mapped is already a RAW URI path. The component constructor treats
        // '%' as literal text and would turn an account path like %20 into
        // %2520, breaking hosted ruTorrent installations. These components
        // were validated by URI above; assemble their raw spelling unchanged.
        return URI.create("${uri.scheme.lowercase()}://${uri.rawAuthority}$mapped").toASCIIString()
    }
}
