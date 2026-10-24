package io.github.gamesbyian.chunks.rpc

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Only run against ephemeral loopback rTorrent in a disposable CI job. */
class RTorrentDisposableServerTest {
    @Test fun realLegacyServerReadOnlyCompatibility() = runBlocking {
        val endpoint = System.getenv("CHUNKS_DISPOSABLE_RT_URL")
        assumeTrue("Only enable in ephemeral CI", !endpoint.isNullOrBlank())
        require(endpoint!!.startsWith("http://127.0.0.1:")) {
            "This acceptance test must never contact a real remote server"
        }
        val client = RTorrentRemote(RemoteCredentials(endpoint, "", ""))
        val version = client.version()
        assertTrue(version.isNotBlank())
        assertTrue(client.listTransfers().isEmpty())
        assertEquals(version, client.probe().version)
    }
}
