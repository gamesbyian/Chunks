package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.rpc.ClientRefusedException
import io.github.gamesbyian.chunks.rpc.RpcHttpStatusException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RemoteProtocolConnectTest {
    private fun n(kind: RemoteBackendKind, url: String) = RemoteProtocolConnect.normalizeEndpoint(kind, url)

    @Test fun browserPagesBecomeExactApiEndpoints() {
        val t = RemoteBackendKind.TRANSMISSION
        assertEquals("https://h.example/u/transmission/rpc", n(t, "https://h.example/u/transmission/web/"))
        assertEquals("https://h.example/u/transmission/rpc", n(t, "https://h.example/u/transmission"))
        assertEquals("https://h.example/u/transmission/rpc", n(t, " https://h.example/u/transmission/rpc "))
        assertEquals("https://h.example:9091/transmission/rpc", n(t, "https://h.example:9091"))
        val q = RemoteBackendKind.QBITTORRENT
        assertEquals("https://h.example/u/qbittorrent/", n(q, "https://h.example/u/qbittorrent"))
        assertEquals("https://h.example/u/qbittorrent/", n(q, "https://h.example/u/qbittorrent/"))
        val d = RemoteBackendKind.DELUGE
        assertEquals("https://h.example/u/deluge/json", n(d, "https://h.example/u/deluge/"))
        assertEquals("https://h.example/u/deluge/json", n(d, "https://h.example/u/deluge/json"))
    }

    @Test fun normalizationNeverChangesOriginOrSmugglesQueries() {
        assertEquals("http://h.example/x?y=1", n(RemoteBackendKind.DELUGE, "http://h.example/x?y=1"))
        assertEquals("not a url", n(RemoteBackendKind.QBITTORRENT, "not a url"))
        assertEquals("https://other.example/qb/", n(RemoteBackendKind.QBITTORRENT, "https://other.example/qb"))
    }

    @Test fun explanationsAreFixedAndCredentialFree() {
        assertEquals("The login was refused. Check the username and password.",
            RemoteProtocolConnect.explain(RpcHttpStatusException(401)))
        assertEquals("Could not reach or verify this client. Nothing was changed.",
            RemoteProtocolConnect.explain(IOException("https://user:pw@host/ failed")))
        assertFalse("://" in RemoteProtocolConnect.explain(ClientRefusedException("bad https://x")))
        assertEquals("This Deluge Web UI has 2 daemons; enter the daemon ID to use",
            RemoteProtocolConnect.explain(ClientRefusedException("This Deluge Web UI has 2 daemons; enter the daemon ID to use")))
    }
}
