package io.github.gamesbyian.chunks.rpc

import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Protected read-only reconciliation of the ONE attempted mkdir from
 * GitHub run 43570367968. Does not submit or retry any mutation.
 * Receipts contain only a fixed enum and no server filesystem path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeedhostFolderCreateReconcileReadOnlyTest {
    @Test fun inspectOnlyThePreviousOneTimeFolder() = runBlocking {
        // These fixed checkpoints describe which read-only preflight completed.
        // Never print raw exceptions, account paths, or provider responses.
        var checkpoint = "entered"
        try {
        assumeTrue(System.getenv("CHUNKS_FOLDER_RECONCILE_ONLY") == "authorized")
        checkpoint = "opt-in-confirmed"
        val runId = System.getenv("CHUNKS_FOLDER_RECONCILE_RUN").orEmpty()
        require(runId == "43570367968") { "Unrecognized historical mkdir run" }
        val rawUrl = System.getenv("CHUNKS_LIVE_RUTORRENT_URL").orEmpty()
        val uri = URI(rawUrl)
        require(uri.scheme == "https" && uri.host?.endsWith(".seedhost.eu") == true &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port == 443) &&
            uri.path.trimEnd('/') == "/rutorrent") {
            "Not the approved primary Seedhost account"
        }
        val user = System.getenv("CHUNKS_LIVE_RTORRENT_USER").orEmpty()
        val pass = System.getenv("CHUNKS_LIVE_RTORRENT_PASS").orEmpty()
        require(user.isNotBlank() && pass.isNotBlank()) { "Protected credentials unavailable" }
        val credentials = RemoteCredentials(rawUrl, user, pass)
        // The user-entered /rutorrent/ web URL is NOT a raw XML-RPC URL.
        // Match RTorrentRemote's exact same-account endpoint mapping before
        // invoking either plugin. The previous diagnostic passed the web URL
        // directly and received an unrelated _getdir UNSUPPORTED outcome.
        val rpc = URI(RemoteEndpointHints.rpcAddress(rawUrl))
        require(rpc.host == uri.host && rpc.rawPath == "/rutorrent/plugins/httprpc/action.php" &&
            rpc.scheme == "https" && rpc.rawUserInfo == null && rpc.rawQuery == null &&
            rpc.rawFragment == null) { "Unexpected primary RPC mount" }
        checkpoint = "account-verified"
        val rootResult = RuTorrentDirectoryListing.read(rpc, credentials, "")
        checkpoint = when (rootResult) {
            is RemoteDirectoryLookup.Ready -> "root-observed"
            is RemoteDirectoryLookup.Unavailable -> "root-" +
                rootResult.reason.name.lowercase().replace('_', '-')
        }
        val root = (rootResult as? RemoteDirectoryLookup.Ready)?.page?.path
            ?: error("CHUNKS_CREATE_RECONCILE: root-not-observed")
        val outcome = RuTorrentFileManager.observeChild(
            rpc, credentials, root, root, "Chunks-folder-test-$runId")
        checkpoint = "two-providers-observed"
        // Nothing here calls newDirectory, d.erase, shell or any transfer mutation.
        println("CHUNKS_CREATE_RECONCILE: evidence=" +
            outcome.name.lowercase().replace('_', '-') + " writes=zero")
        assertNotEquals("The historical folder could not be independently classified",
            RemoteFolderReadback.CANNOT_VERIFY, outcome)
        } finally {
            println("CHUNKS_CREATE_RECONCILE: checkpoint=" + checkpoint + " writes=zero")
        }
    }
}
