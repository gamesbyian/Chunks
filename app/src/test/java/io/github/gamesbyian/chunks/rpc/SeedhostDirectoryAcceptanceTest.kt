package io.github.gamesbyian.chunks.rpc

import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Trusted-main, manually dispatched real Seedhost folder probe.
 * Prints only fixed capability categories and counts. Mutates nothing unless
 * a separate explicit workflow input authorizes one new disposable folder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeedhostDirectoryAcceptanceTest {
    @Test fun exactAccountBrowseAndOptionalOneFolderCreation() = runBlocking {
        assumeTrue(System.getenv("CHUNKS_HOST_FOLDER_READONLY") == "authorized")
        val raw = System.getenv("CHUNKS_LIVE_RUTORRENT_URL").orEmpty()
        val user = System.getenv("CHUNKS_LIVE_RTORRENT_USER").orEmpty()
        val pass = System.getenv("CHUNKS_LIVE_RTORRENT_PASS").orEmpty()
        val uri = URI(raw)
        require(uri.scheme == "https" && uri.host?.endsWith(".seedhost.eu") == true &&
            uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.port == -1 || uri.port == 443) && user.isNotBlank() && pass.isNotBlank()) {
            "Protected real-host folder evidence requires a permitted HTTPS account"
        }
        val remote = RTorrentRemote(RemoteCredentials(raw, user, pass))
        // Existing exact-account authentication witness before relying on PHP plugins.
        runCatching { verifySeedhostReadOnlyAcceptance(remote) }.getOrElse {
            error("CHUNKS_SEEDHOST_FOLDER: authenticated-RPC-read-unavailable")
        }
        val listed = remote.browseDirectories("")
        if (listed !is RemoteDirectoryLookup.Ready) {
            val reason = (listed as RemoteDirectoryLookup.Unavailable).reason.name.lowercase()
            println("CHUNKS_SEEDHOST_FOLDER: browse-unavailable-" + reason)
            error("CHUNKS_SEEDHOST_FOLDER: browse-not-verified")
        }
        val root = listed.page.path
        require(root != "/" && RuTorrentDirectoryListing.canonical(root) == root) {
            "CHUNKS_SEEDHOST_FOLDER: unsafe-or-unknown-root"
        }
        println("CHUNKS_SEEDHOST_FOLDER: browse-verified child-count=" +
            listed.page.folders.size)

        val capability = remote.folderCreateCapability(root)
        val canCreate = capability is RemoteFolderCreateCapability.Ready
        println("CHUNKS_SEEDHOST_FOLDER: filemanager-root-verified=" + canCreate)
        if (capability is RemoteFolderCreateCapability.Unavailable) {
            // Stable enum only; never expose returned server paths or responses.
            println("CHUNKS_SEEDHOST_FOLDER: filemanager-reason=" +
                capability.reason.name.lowercase().replace('_', '-'))
        }
        if (System.getenv("CHUNKS_HOST_FOLDER_CREATE") == "authorized") {
            require(canCreate) { "CHUNKS_SEEDHOST_FOLDER: create-capability-unavailable" }
            val id = System.getenv("CHUNKS_HOST_FOLDER_RUN_ID").orEmpty()
            require(id.matches(Regex("[0-9]{7,20}"))) {
                "CHUNKS_SEEDHOST_FOLDER: invalid-test-identifier"
            }
            val leaf = "Chunks-folder-test-" + id
            val result = remote.createServerFolder(root, root, leaf)
            if (result !is RemoteFolderCreateOutcome.Created ||
                result.path != root.trimEnd('/') + "/" + leaf) {
                println("CHUNKS_SEEDHOST_FOLDER: create-outcome=" + when (result) {
                    RemoteFolderCreateOutcome.AlreadyExists -> "already-exists"
                    RemoteFolderCreateOutcome.Refused -> "refused"
                    RemoteFolderCreateOutcome.Uncertain -> "uncertain"
                    else -> "unverified"
                })
                error("CHUNKS_SEEDHOST_FOLDER: create-not-verified; no-repeat")
            }
            println("CHUNKS_SEEDHOST_FOLDER: created-single-child-and-independently-relisted")
        } else {
            println("CHUNKS_SEEDHOST_FOLDER: read-only-no-mutation")
        }
    }
}
