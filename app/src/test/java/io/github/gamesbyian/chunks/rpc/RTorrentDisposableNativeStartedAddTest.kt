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

    /**
     * Genuine rTorrent (legacy and modern), not an XML-RPC mock. A multi-file
     * .torrent's reviewed destination is the parent, while d.directory reports
     * the child root. No files are copied or deleted; this test only creates a
     * disposable session job and uses read-only getters after one Add.
     */
    @Test fun realMultiFileBooleanAndStoppedRootAreReadBackFromEngine() = runBlocking {
        val remote = client()
        val parent = destination()
        val rootName = "chunks-multifile-" + System.nanoTime()
        val first = "first-file".toByteArray(Charsets.UTF_8)
        val second = "second-file".toByteArray(Charsets.UTF_8)
        val pieceHash = java.security.MessageDigest.getInstance("SHA-1")
            .digest(first + second)
        val infoPrefix = (
            "d5:filesl" +
                "d6:lengthi${first.size}e4:pathl9:alpha.txtee" +
                "d6:lengthi${second.size}e4:pathl8:beta.txtee" +
                "e4:name${rootName.length}:$rootName" +
                "12:piece lengthi16384e6:pieces20:"
        ).toByteArray(Charsets.ISO_8859_1)
        val metainfo = (
            "d8:announce27:http://127.0.0.1:9/announce4:info"
        ).toByteArray(Charsets.ISO_8859_1) + infoPrefix + pieceHash +
            byteArrayOf('e'.code.toByte(), 'e'.code.toByte())
        val preview = TorrentMetainfoReader.preview(metainfo)
        assertEquals(2, preview.fileCount)
        assertEquals(rootName, preview.name)
        val hash = preview.infoHashV1
        assertNull("The disposable identity must be new", remote.lookupName(hash))

        // The only write in this case. Never retry even if its reply is lost.
        val firstResult = remote.loadStoppedTorrentBytes(metainfo, parent)
        val plan = StoppedRemoteAddPlan.fromMagnet(
            "magnet:?xt=urn:btih:$hash", parent)
        var observed: RemoteAddOutcome = firstResult
        for (attempt in 0 until 10) {
            if (observed is RemoteAddOutcome.VerifiedStopped) break
            observed = remote.reconcileStoppedAdd(plan) // read-only after uncertain Add
            if (observed !is RemoteAddOutcome.VerifiedStopped && attempt < 9)
                kotlinx.coroutines.delay(250L)
        }
        val expectedRoot = "$parent/$rootName"
        assertEquals(RemoteAddOutcome.VerifiedStopped(hash, expectedRoot), observed)
        assertEquals(rootName, remote.lookupName(hash))
        assertEquals(1L, remote.call("d.is_multi_file", listOf(hash)))
        assertEquals(expectedRoot, remote.call("d.directory", listOf(hash)))
        assertEquals(2, remote.listFiles(hash).size)
    }
}
