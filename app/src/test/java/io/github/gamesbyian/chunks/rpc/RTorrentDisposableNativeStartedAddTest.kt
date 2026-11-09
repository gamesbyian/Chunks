package io.github.gamesbyian.chunks.rpc

import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real localhost rTorrent 0.9.8/0.16.x; never run against a protected account. */
class RTorrentDisposableNativeStartedAddTest {
    private fun client(): RTorrentRemote {
        val endpoint = System.getenv("CHUNKS_DISPOSABLE_RT_URL").orEmpty()
        assumeTrue("Requires a throwaway loopback daemon", endpoint.isNotBlank())
        val uri = URI(endpoint)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" &&
            uri.port == 18080 && uri.path == "/RPC2" && uri.userInfo == null &&
            uri.query == null && uri.fragment == null) {
            "Refusing to mutate any non-disposable rTorrent"
        }
        return RTorrentRemote(RemoteCredentials(endpoint, "", ""))
    }

    private fun destination(): String {
        val path = System.getenv("CHUNKS_DISPOSABLE_TARGET_DIR").orEmpty()
        require(path.startsWith("/") && path.contains("chunks-data")) {
            "Not the disposable runner data root"
        }
        return path
    }

    private suspend fun expectActive(remote: RTorrentRemote, hash: String, parent: String) {
        var last: RemoteTransfer? = null
        var active = false
        for (attempt in 0 until 16) {
            last = remote.listTransfers("main").singleOrNull { it.hash.equals(hash, true) }
            if (last != null) {
                active = remote.readActivity(hash).active == true
                if (active) break
            }
            if (attempt < 15) Thread.sleep(400)
        }
        assertNotNull("New native-Start Add must be registered under exact hash", last)
        assertEquals("A custom Add must honor its selected parent", parent, last!!.directory)
        assertTrue("Native start-on-load must activate, not merely set d.state", active)
    }

    @Test fun newBinaryUsesRealNativeStartOnLoadAndBecomesActive() = runBlocking {
        val remote = client()
        val parent = destination()
        val bytes = (
            "d4:info" +
                "d6:lengthi5e4:name10:native.txt12:piece lengthi16384e6:pieces20:" +
                "01234567890123456789ee"
        ).toByteArray(Charsets.UTF_8)
        val hash = TorrentMetainfoReader.preview(bytes).infoHashV1
        assertFalse(remote.listTransfers("main").any { it.hash.equals(hash, true) })
        val result = remote.loadStartedTorrentBytes(bytes, parent)
        assertTrue("No lost/broken native load acknowledgement: $result",
            result is RemoteAddAndStart.Started ||
                result is RemoteAddAndStart.StartNotVerified)
        expectActive(remote, hash, parent)
        assertEquals(RemoteAddAndStart.AddedStopped(RemoteAddOutcome.AlreadyPresent(hash)),
            remote.loadStartedTorrentBytes(bytes, parent))
    }

    @Test fun newMagnetUsesRealNativeStartOnLoadAndBecomesActive() = runBlocking {
        val remote = client()
        val parent = destination()
        val hash = "17".repeat(20)
        val magnet = "magnet:?xt=urn:btih:$hash&tr=http%3A%2F%2F127.0.0.1%3A9%2Fannounce"
        assertFalse(remote.listTransfers("main").any { it.hash.equals(hash, true) })
        val result = remote.loadStarted(StoppedRemoteAddPlan.fromMagnet(magnet, parent))
        assertTrue("No lost/broken native load acknowledgement: $result",
            result is RemoteAddAndStart.Started ||
                result is RemoteAddAndStart.StartNotVerified)
        expectActive(remote, hash, parent)
    }

}
