package io.github.gamesbyian.chunks.rpc

import java.io.IOException

/**
 * A genuine HTTP status observed by the transport, not an arbitrary exception
 * message supplied by a gateway, parser, proxy or nested library.
 *
 * Smart Connect may try another hostname-discovered path only after an actual
 * 404 response from its own read-only RPC request.
 */
internal class RpcHttpStatusException(val status: Int) :
    IOException("rTorrent gateway returned HTTP $status") {
    init { require(status in 100..599) }
}
