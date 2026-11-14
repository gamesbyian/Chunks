package io.github.gamesbyian.chunks.rpc

import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * No mutation. Reconcile a unique historical nested test-folder name without
 * knowing which candidate parent (if any) the previous invocation reached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeedhostNestedCreateReconcileReadOnlyTest {
    @Test fun inspectPreviousNestedOneShotAcrossOriginalTenParents() = runBlocking {
        assumeTrue(System.getenv("CHUNKS_NESTED_RECONCILE_ONLY") == "authorized")
        val raw = System.getenv("CHUNKS_LIVE_RUTORRENT_URL").orEmpty()
        val user = System.getenv("CHUNKS_LIVE_RTORRENT_USER").orEmpty()
        val pass = System.getenv("CHUNKS_LIVE_RTORRENT_PASS").orEmpty()
        val runId = System.getenv("CHUNKS_NESTED_RECONCILE_RUN").orEmpty()
        require(runId == "43643911533") { "Unexpected prior run identifier" }
        val uri = URI(raw)
        require(uri.scheme == "https" && uri.host?.endsWith(".seedhost.eu") == true &&
            (uri.port == -1 || uri.port == 443) && uri.path.trimEnd('/') == "/rutorrent" &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            user.isNotBlank() && pass.isNotBlank())
        val rpc = URI(RemoteEndpointHints.rpcAddress(raw))
        require(rpc.scheme == "https" && rpc.host == uri.host &&
            rpc.rawPath == "/rutorrent/plugins/httprpc/action.php")
        val remote = RTorrentRemote(RemoteCredentials(raw, user, pass))
        val page = (remote.browseDirectories("") as? RemoteDirectoryLookup.Ready)?.page
            ?: error("CHUNKS_NESTED_RECONCILE: root-unavailable")
        val root = page.path
        require(root != "/" && RuTorrentDirectoryListing.canonical(root) == root)
        println("CHUNKS_NESTED_RECONCILE: root-observed=yes candidate-count=" +
            page.folders.take(10).size + " writes=zero")
        val leaf = "Chunks-nested-create-" + runId
        var browserCount = 0
        var verifiedCount = 0
        var availableParentCount = 0
        for ((index, folder) in page.folders.take(10).withIndex()) {
            val parent = root.trimEnd('/') + "/" + folder
            val child = remote.browseDirectories(parent) as? RemoteDirectoryLookup.Ready
            val browserHasTarget = child?.page?.path == parent && leaf in child.page.folders
            if (browserHasTarget) browserCount++
            val capable = remote.folderCreateCapability(root, parent) is RemoteFolderCreateCapability.Ready
            if (capable) availableParentCount++
            val observation = remote.observeServerFolder(root, parent, leaf)
            if (observation == RemoteFolderReadback.VERIFIED_DIRECTORY) verifiedCount++
            println("CHUNKS_NESTED_RECONCILE: sample=" + (index+1) +
                " browser-child=" + browserHasTarget +
                " parent-eligible=" + capable +
                " evidence=" + observation.name.lowercase().replace('_','-') +
                " writes=zero")
        }
        println("CHUNKS_NESTED_RECONCILE: browser-target-count=" + browserCount +
            " independently-verified-target-count=" + verifiedCount +
            " parent-eligible-count=" + availableParentCount + " writes=zero")
        assertTrue(browserCount <= 10 && verifiedCount <= browserCount)
        println("CHUNKS_NESTED_RECONCILE: completed=read-only writes=zero")
    }
}
