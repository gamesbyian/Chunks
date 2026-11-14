package io.github.gamesbyian.chunks.rpc

import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A one-shot, strictly read-only diagnostic, never part of ordinary Android CI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeedhostInventoryCoverageReadOnlyTest {
    @Test fun inspectDirectoryTypesAndCoverageWithoutPaths() = runBlocking {
        assumeTrue(System.getenv("CHUNKS_SEEDHOST_FORENSIC_ONLY") == "authorized")
        val raw = System.getenv("CHUNKS_LIVE_RUTORRENT_URL").orEmpty()
        val user = System.getenv("CHUNKS_LIVE_RTORRENT_USER").orEmpty()
        val pass = System.getenv("CHUNKS_LIVE_RTORRENT_PASS").orEmpty()
        val uri = URI(raw)
        require(uri.scheme == "https" && uri.host?.endsWith(".seedhost.eu") == true &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port == 443) && uri.path.trimEnd('/') == "/rutorrent" &&
            user.isNotBlank() && pass.isNotBlank()) {
            "Invalid exact-account read-only forensic context"
        }
        val rpc = URI(RemoteEndpointHints.rpcAddress(raw))
        require(rpc.scheme == "https" && rpc.host == uri.host &&
            rpc.rawPath == "/rutorrent/plugins/httprpc/action.php" &&
            rpc.rawQuery == null && rpc.rawUserInfo == null)
        val credentials = RemoteCredentials(raw, user, pass)
        val root = (RuTorrentDirectoryListing.read(rpc, credentials, "") as?
            RemoteDirectoryLookup.Ready)?.page?.path
            ?: error("CHUNKS_INVENTORY_FORENSIC: root-unavailable")
        require(root != "/" && RuTorrentDirectoryListing.canonical(root) == root)
        val metrics = RuTorrentFileManager.forensicInventoryReadOnly(
            rpc, credentials, root, "Chunks-folder-test-43587333772")
            ?: error("CHUNKS_INVENTORY_FORENSIC: corroboration-unavailable")
        println("CHUNKS_INVENTORY_FORENSIC: browser=" + metrics.browserDirectories +
            " manager-entries=" + metrics.managerEntries +
            " manager-typed=" + metrics.managerDirectories +
            " shared-typed=" + metrics.sharedTyped +
            " browser-as-untyped=" + metrics.browserAlsoNonDirectory +
            " manager-extra-typed=" + metrics.managerOnlyTyped +
            " response-chars=" + metrics.responseChars)
        println("CHUNKS_INVENTORY_FORENSIC: first-matched=" + metrics.firstBrowserTyped +
            " last-matched=" + metrics.lastBrowserTyped +
            " first-index=" + metrics.earliestTypedIndex +
            " last-index=" + metrics.latestTypedIndex +
            " target-browser=" + metrics.targetInBrowser +
            " target-manager-typed=" + metrics.targetInManager +
            " target-manager-untyped=" + metrics.targetInManagerAsNonDirectory +
            " writes=zero")
        assertTrue("Malformed inventory metrics",
            metrics.sharedTyped <= metrics.managerDirectories &&
            metrics.managerDirectories <= metrics.managerEntries &&
            metrics.sharedTyped <= metrics.browserDirectories)
        println("CHUNKS_INVENTORY_FORENSIC: completed=read-only writes=zero")
    }
}
