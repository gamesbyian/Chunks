package io.github.gamesbyian.chunks.rpc

import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compare bounded nested read-only inventories; never print paths or names. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeedhostNestedCoverageReadOnlyTest {
    @Test fun compareThreeVerifiedNestedParentInventories() = runBlocking {
        assumeTrue(System.getenv("CHUNKS_SEEDHOST_NESTED_ONLY") == "authorized")
        val raw = System.getenv("CHUNKS_LIVE_RUTORRENT_URL").orEmpty()
        val user = System.getenv("CHUNKS_LIVE_RTORRENT_USER").orEmpty()
        val pass = System.getenv("CHUNKS_LIVE_RTORRENT_PASS").orEmpty()
        val uri = URI(raw)
        require(uri.scheme == "https" && uri.host?.endsWith(".seedhost.eu") == true &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.path.trimEnd('/') == "/rutorrent" && (uri.port == -1 || uri.port == 443) &&
            user.isNotBlank() && pass.isNotBlank())
        val rpc = URI(RemoteEndpointHints.rpcAddress(raw))
        require(rpc.scheme == "https" && rpc.host == uri.host &&
            rpc.rawPath == "/rutorrent/plugins/httprpc/action.php")
        val credentials = RemoteCredentials(raw, user, pass)
        val root = (RuTorrentDirectoryListing.read(rpc, credentials, "") as?
            RemoteDirectoryLookup.Ready)?.page?.path ?: error("CHUNKS_NESTED_FORENSIC: root-unavailable")
        require(root != "/" && RuTorrentDirectoryListing.canonical(root) == root)
        val metrics = RuTorrentFileManager.forensicNestedCoverageReadOnly(rpc, credentials, root)
        for ((index, m) in metrics.withIndex()) {
            println("CHUNKS_NESTED_FORENSIC: sample=" + (index + 1) +
                " browser=" + m.browserDirectories +
                " manager-total=" + m.managerEntries +
                " manager-directories=" + m.managerDirectories +
                " shared=" + m.sharedTyped +
                " response-chars=" + m.responseChars + " writes=zero")
        }
        assertTrue("No independently verified nested folder sampled", metrics.isNotEmpty())
        println("CHUNKS_NESTED_FORENSIC: completed=read-only samples=" +
            metrics.size + " writes=zero")
    }
}
