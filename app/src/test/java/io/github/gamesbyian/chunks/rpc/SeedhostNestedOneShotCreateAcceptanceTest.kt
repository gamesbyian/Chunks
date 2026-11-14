package io.github.gamesbyian.chunks.rpc

import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Explicit one-shot real host acceptance for an independently verified small
 * nested directory. Only a new disposable empty folder is ever created.
 * No torrents, files, renames, removals or automatic cleanup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeedhostNestedOneShotCreateAcceptanceTest {
    @Test fun onlyOneNewChildUnderACompletelyCorroboratedNestedParent() = runBlocking {
        assumeTrue(System.getenv("CHUNKS_SEEDHOST_NESTED_CREATE_ONCE") == "authorized")
        val raw = System.getenv("CHUNKS_LIVE_RUTORRENT_URL").orEmpty()
        val user = System.getenv("CHUNKS_LIVE_RTORRENT_USER").orEmpty()
        val pass = System.getenv("CHUNKS_LIVE_RTORRENT_PASS").orEmpty()
        val runId = System.getenv("CHUNKS_NESTED_CREATE_RUN_ID").orEmpty()
        require(runId.matches(Regex("[0-9]{7,20}")))
        val uri = URI(raw)
        require(uri.scheme == "https" && uri.host?.endsWith(".seedhost.eu") == true &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port == 443) && uri.path.trimEnd('/') == "/rutorrent" &&
            user.isNotBlank() && pass.isNotBlank()) {
            "CHUNKS_NESTED_CREATE_ONCE: invalid protected context"
        }
        val rpc = URI(RemoteEndpointHints.rpcAddress(raw))
        require(rpc.scheme == "https" && rpc.host == uri.host &&
            rpc.rawPath == "/rutorrent/plugins/httprpc/action.php" &&
            rpc.rawUserInfo == null && rpc.rawQuery == null)
        val remote = RTorrentRemote(RemoteCredentials(raw, user, pass))
        // Verify exact primary rTorrent session first, not just reachable PHP.
        verifySeedhostReadOnlyAcceptance(remote)
        val page = (remote.browseDirectories("") as? RemoteDirectoryLookup.Ready)?.page
            ?: error("CHUNKS_NESTED_CREATE_ONCE: browser-root-unavailable")
        val root = page.path
        require(root != "/" && RuTorrentDirectoryListing.canonical(root) == root)
        val candidateParents = page.folders.take(10).map { root.trimEnd('/') + "/" + it }
        var selected: String? = null
        for (candidate in candidateParents) {
            // Prefer an already-empty subdirectory to avoid disturbing the
            // user's existing catalogue layout. No newDirectory is sent until
            // the selected exact parent has complete two-provider agreement.
            val page = remote.browseDirectories(candidate) as? RemoteDirectoryLookup.Ready
            if (page?.page?.path == candidate && page.page.folders.isEmpty() &&
                remote.folderCreateCapability(root, candidate) is RemoteFolderCreateCapability.Ready) {
                selected = candidate
                break
            }
        }
        val parent = selected
            ?: error("CHUNKS_NESTED_CREATE_ONCE: no-independent-nested-parent; writes-zero")
        val leaf = "Chunks-nested-create-" + runId
        require(RuTorrentFileManager.validLeaf(leaf))
        println("CHUNKS_NESTED_CREATE_ONCE: exact-parent-two-provider-preflight=verified")
        // The production adapter performs another fresh complete parent check.
        // This is the only operation that can write, and it is never retried.
        val result = remote.createServerFolder(root, parent, leaf)
        if (result is RemoteFolderCreateOutcome.Created &&
            result.path == parent.trimEnd('/') + "/" + leaf) {
            println("CHUNKS_NESTED_CREATE_ONCE: one-empty-child-created-receipt-and-two-provider-readback=verified")
            assertEquals(parent.trimEnd('/') + "/" + leaf, result.path)
        } else {
            val resultTag = when (result) {
                RemoteFolderCreateOutcome.AlreadyExists -> "already-exists"
                RemoteFolderCreateOutcome.Refused -> "refused"
                RemoteFolderCreateOutcome.Uncertain -> "uncertain"
                else -> "unexpected"
            }
            println("CHUNKS_NESTED_CREATE_ONCE: outcome=" + resultTag + " no-retry")
            val observed = remote.observeServerFolder(root, parent, leaf)
            println("CHUNKS_NESTED_CREATE_ONCE: read-only-reconcile=" +
                observed.name.lowercase().replace('_', '-') + " no-retry")
            error("CHUNKS_NESTED_CREATE_ONCE: no verified mkdir success; do not replay")
        }
    }
}
