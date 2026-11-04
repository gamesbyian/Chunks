package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.RpcHttpStatusException
import org.junit.Assert.assertEquals
import org.junit.Test

/** C2: offline, sign-in rejection, unresponsive and gateway-only failures stay distinct. */
class CombinedListingFailureTest {
    @Test fun distinguishesAuthTimeoutGatewayAndOffline() {
        assertEquals("sign-in rejected; check this saved login", listingFailure(RpcHttpStatusException(401)))
        assertEquals("sign-in rejected; check this saved login", listingFailure(RpcHttpStatusException(403)))
        assertEquals("server reached but rTorrent did not answer (HTTP 502)",
            listingFailure(RpcHttpStatusException(502)))
        assertEquals("server returned HTTP 404", listingFailure(RpcHttpStatusException(404)))
        assertEquals("did not answer in time", listingFailure(java.net.SocketTimeoutException("read")))
        assertEquals("connection refused", listingFailure(java.net.ConnectException("refused")))
        assertEquals("network error", listingFailure(java.io.IOException("reset")))
        assertEquals("unexpected reply", listingFailure(IllegalStateException("parse")))
    }
}
