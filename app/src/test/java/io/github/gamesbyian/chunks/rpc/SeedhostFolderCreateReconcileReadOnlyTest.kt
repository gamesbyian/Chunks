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
        assumeTrue(System.getenv("CHUNKS_FOLDER_RECONCILE_ONLY") == "authorized")
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
        val root = (RuTorrentDirectoryListing.read(uri, credentials, "") as?
            RemoteDirectoryLookup.Ready)?.page?.path
            ?: error("CHUNKS_CREATE_RECONCILE: root-not-observed")
        val outcome = RuTorrentFileManager.observeChild(
            uri, credentials, root, root, "Chunks-folder-test-$runId")
        // Nothing here calls newDirectory, d.erase, shell or any transfer mutation.
        println("CHUNKS_CREATE_RECONCILE: evidence=" +
            outcome.name.lowercase().replace('_', '-') + " writes=zero")
        assertNotEquals("The historical folder could not be independently classified",
            RemoteFolderReadback.CANNOT_VERIFY, outcome)
    }
}
