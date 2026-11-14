package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Disposable same-tenant File Manager contract, with real HTTP Basic challenge,
 * exact root readback and one authorized newDirectory POST. No public network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RuTorrentFolderCreateHttpTest {
    private inner class Fixture : AutoCloseable {
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val base get() = "http://127.0.0.1:" + server.address.port
        val rpc get() = base + "/tenant/rutorrent2/plugins/httprpc/action.php"
        val authHeader = "Basic " + Base64.getEncoder()
            .encodeToString("alice:secret".toByteArray(Charsets.UTF_8))
        val created = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val writes = AtomicInteger()
        var requireFreshDigest = false
        val observedDigestHeaders = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val targets = java.util.Collections.synchronizedList(mutableListOf<String>())
        val siblingCalls = AtomicInteger()
        var configuredRoot = "/srv/tenant"
        var configStatus = 200
        var listingStatus = 200
        var symlinkParent = false
        var symlinkBrowserRoot = false
        var symlinkChildAfterWrite = false
        var applyWrite = true
        var ackStatus = 200
        private fun send(ex: HttpExchange, code: Int, body: String = "") {
            val bytes = body.toByteArray(Charsets.UTF_8)
            if (code == 401)
                ex.responseHeaders.add("WWW-Authenticate",
                    if (requireFreshDigest)
                        "Digest realm=\"tenant\", nonce=\"disposable-fixed\", qop=\"auth\", algorithm=SHA-256"
                    else "Basic realm=\"tenant\"")
            if (code == 200) {
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            } else {
                ex.sendResponseHeaders(code, -1)
                ex.close()
            }
        }
        private fun folders(path: String): List<String> =
            when (path) {
                "/srv/tenant" -> listOf("TV")
                "/srv/tenant/TV" -> listOf("Season 1") + created.toList()
                else -> emptyList()
            }
        init {
            server.createContext("/tenant/rutorrent2/plugins/_getdir/listdir.php") { ex ->
                require(ex.requestMethod == "GET")
                val dir = URLDecoder.decode(
                    ex.requestURI.rawQuery.orEmpty().substringAfter("dir=", ""),
                    Charsets.UTF_8.name()).ifEmpty { "/srv/tenant" }
                send(ex, 200, JSONObject().put("path", dir)
                    .put("directories", JSONArray(folders(dir))).put("files", JSONArray()).toString())
            }
            server.createContext("/tenant/rutorrent2/plugins/filemanager/action.php") { ex ->
                require(ex.requestMethod == "POST")
                val body = ex.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                val suppliedAuthorization = ex.requestHeaders.getFirst("Authorization")
                val authorized = if (requireFreshDigest) {
                    suppliedAuthorization?.startsWith("Digest ") == true &&
                        observedDigestHeaders.add(suppliedAuthorization)
                } else suppliedAuthorization == authHeader
                if (!authorized) {
                    send(ex, 401)
                } else {
                    val command = JSONObject(URLDecoder.decode(
                        body.substringAfter("action="), Charsets.UTF_8.name()))
                    when (command.getString("method")) {
                        "getConfig" -> send(ex, configStatus,
                            JSONObject().put("homedir", configuredRoot).toString())
                        "listDirectory" -> {
                            val path = command.getString("dir").trim('/').let {
                                if (it.isEmpty()) configuredRoot
                                else configuredRoot.trimEnd('/') + "/" + it
                            }
                            val names = when (path) {
                                "/srv" -> listOf(if (symlinkBrowserRoot) "tenant" else "tenant/")
                                "/srv/tenant" -> listOf(if (symlinkParent) "TV" else "TV/")
                                "/srv/tenant/TV" -> listOf("Season 1/") +
                                    created.map { it + if (symlinkChildAfterWrite) "" else "/" }
                                else -> emptyList()
                            }
                            send(ex, listingStatus, JSONObject().put("listing",
                                JSONArray(names.map { JSONObject().put("name", it) })).toString())
                        }
                        "newDirectory" -> {
                            writes.incrementAndGet()
                            val target = command.getString("target")
                            targets.add(target)
                            if (applyWrite && (target.startsWith("TV/") ||
                                    target.startsWith("tenant/TV/")))
                                created.add(target.substringAfterLast('/'))
                            send(ex, ackStatus, JSONObject().put("error", false).toString())
                        }
                        else -> send(ex, 404)
                    }
                }
            }
            server.createContext("/other-tenant/") { ex ->
                siblingCalls.incrementAndGet()
                send(ex, 200, "{}")
            }
            server.start()
        }
        fun remote() = RTorrentRemote(RemoteCredentials(rpc, "alice", "secret"))
        override fun close() = server.stop(0)
    }

    @Test fun verifiedRootAllowsOnlyOneChildAndIndependentlyWitnessesCreation() = runBlocking {
        Fixture().use { fx ->
            val remote = fx.remote()
            assertEquals(RemoteFolderCreateCapability.Ready("/srv/tenant"),
                remote.folderCreateCapability("/srv/tenant"))
            val result = remote.createServerFolder("/srv/tenant",
                "/srv/tenant/TV", "Season 2")
            assertEquals(RemoteFolderCreateOutcome.Created("/srv/tenant/TV/Season 2"), result)
            assertEquals(1, fx.writes.get())
            assertEquals(0, fx.siblingCalls.get())
            assertEquals(listOf("Season 1", "Season 2"),
                (remote.browseDirectories("/srv/tenant/TV") as
                    RemoteDirectoryLookup.Ready).page.folders)
        }
    }

    @Test fun digestAuthenticatedMkdirRequiresFreshRequestProofAndNeverReplaysWrite() = runBlocking {
        Fixture().use { fx ->
            fx.requireFreshDigest = true
            val remote = fx.remote()
            assertEquals(RemoteFolderCreateCapability.Ready("/srv/tenant"),
                remote.folderCreateCapability("/srv/tenant"))
            val outcome = remote.createServerFolder(
                "/srv/tenant", "/srv/tenant/TV", "Digest-child")
            assertEquals(RemoteFolderCreateOutcome.Created("/srv/tenant/TV/Digest-child"),
                outcome)
            assertEquals("Only one authorized mutation", 1, fx.writes.get())
            assertEquals(0, fx.siblingCalls.get())
            assertTrue("Capability and write must not reuse a Digest header",
                fx.observedDigestHeaders.size >= 3)
        }
    }

    @Test fun verifiedAncestorRootMapsTheExactChildIntoFileManagerNamespace() = runBlocking {
        Fixture().use { fx ->
            // _getdir starts in the download subtree, but File Manager exposes
            // an ancestor homedir. Chunks proves every path component is a
            // directory and submits the correct File Manager-relative target.
            fx.configuredRoot = "/srv"
            val remote = fx.remote()
            assertEquals(RemoteFolderCreateCapability.Ready("/srv/tenant"),
                remote.folderCreateCapability("/srv/tenant"))
            val outcome = remote.createServerFolder(
                "/srv/tenant", "/srv/tenant/TV", "Season 2")
            assertEquals(RemoteFolderCreateOutcome.Created("/srv/tenant/TV/Season 2"), outcome)
            assertEquals(listOf("tenant/TV/Season 2"), fx.targets)
            assertEquals(1, fx.writes.get())
            assertEquals(0, fx.siblingCalls.get())
        }
    }

    @Test fun ancestorMappingRefusesSymlinkRootOrUnrelatedParentWithoutWriting() = runBlocking {
        Fixture().use { fx ->
            fx.configuredRoot = "/srv"
            fx.symlinkBrowserRoot = true
            val remote = fx.remote()
            assertEquals(RemoteFolderCreateCapability.Unavailable(
                RemoteFolderCreateCapability.Reason.ROOT_MISMATCH),
                remote.folderCreateCapability("/srv/tenant"))
            assertEquals(RemoteFolderCreateOutcome.Refused,
                remote.createServerFolder("/srv/tenant", "/srv/tenant/TV", "New"))
            assertEquals(RemoteFolderCreateOutcome.Refused,
                remote.createServerFolder("/srv/tenant", "/srv/other/TV", "New"))
            assertEquals(0, fx.writes.get())
        }
    }

    @Test fun foreignRootSymlinkParentExistingAndTraversalRefuseBeforeWrite() = runBlocking {
        Fixture().use { fx ->
            val remote = fx.remote()
            assertTrue(remote.folderCreateCapability("/srv/other") is
                RemoteFolderCreateCapability.Unavailable)
            assertEquals(RemoteFolderCreateOutcome.Refused,
                remote.createServerFolder("/srv/other", "/srv/other/TV", "New"))
            assertEquals(RemoteFolderCreateOutcome.Refused,
                remote.createServerFolder("/srv/tenant", "/srv/tenant/TV", "../escape"))
            assertEquals(RemoteFolderCreateOutcome.AlreadyExists,
                remote.createServerFolder("/srv/tenant", "/srv/tenant/TV", "Season 1"))
            fx.symlinkParent = true
            assertEquals(RemoteFolderCreateOutcome.Refused,
                remote.createServerFolder("/srv/tenant", "/srv/tenant/TV", "Season 2"))
            assertEquals(0, fx.writes.get())
        }
    }

    @Test fun symlinkAppearingAfterWriteCannotBeMistakenForVerifiedDirectory() = runBlocking {
        Fixture().use { fx ->
            // _getdir's PHP is_dir follows symlinks; the File Manager listing
            // must separately classify this entry as a directory (suffix '/').
            fx.symlinkChildAfterWrite = true
            val outcome = fx.remote().createServerFolder(
                "/srv/tenant", "/srv/tenant/TV", "Raced-child")
            assertEquals(RemoteFolderCreateOutcome.Uncertain, outcome)
            assertEquals("Never replay a write whose target changed type", 1, fx.writes.get())
        }
    }

    @Test fun readOnlyCapabilityExplainsMissingPluginDeniedAccessAndMismatchedRoot() = runBlocking {
        Fixture().use { fx ->
            val remote = fx.remote()
            fx.configStatus = 404
            assertEquals(RemoteFolderCreateCapability.Unavailable(
                RemoteFolderCreateCapability.Reason.NOT_INSTALLED),
                remote.folderCreateCapability("/srv/tenant"))
            fx.configStatus = 403
            assertEquals(RemoteFolderCreateCapability.Unavailable(
                RemoteFolderCreateCapability.Reason.ACCESS_DENIED),
                remote.folderCreateCapability("/srv/tenant"))
            fx.configStatus = 500
            assertEquals(RemoteFolderCreateCapability.Unavailable(
                RemoteFolderCreateCapability.Reason.CANNOT_VERIFY),
                remote.folderCreateCapability("/srv/tenant"))
            fx.configStatus = 200
            fx.configuredRoot = "/srv/different"
            assertEquals(RemoteFolderCreateCapability.Unavailable(
                RemoteFolderCreateCapability.Reason.ROOT_MISMATCH),
                remote.folderCreateCapability("/srv/tenant"))
            fx.configuredRoot = "/srv/tenant"
            fx.listingStatus = 403
            assertEquals(RemoteFolderCreateCapability.Unavailable(
                RemoteFolderCreateCapability.Reason.ACCESS_DENIED),
                remote.folderCreateCapability("/srv/tenant"))
            assertEquals("Capability diagnosis must be read-only", 0, fx.writes.get())
            assertEquals(0, fx.siblingCalls.get())
        }
    }

    @Test fun readOnlyReconciliationNeverReplaysMkdirAndRequiresTwoIndependentWitnesses() = runBlocking {
        Fixture().use { fx ->
            fx.created.add("Historical")
            val creds = RemoteCredentials(fx.rpc, "alice", "secret")
            val rpc = java.net.URI(fx.rpc)
            assertEquals(RemoteFolderReadback.VERIFIED_DIRECTORY,
                RuTorrentFileManager.observeChild(rpc, creds,
                    "/srv/tenant/TV", "/srv/tenant/TV", "Historical"))
            assertEquals(RemoteFolderReadback.NOT_PRESENT,
                RuTorrentFileManager.observeChild(rpc, creds,
                    "/srv/tenant/TV", "/srv/tenant/TV", "Never-created"))
            fx.symlinkChildAfterWrite = true
            assertEquals(RemoteFolderReadback.INCONSISTENT,
                RuTorrentFileManager.observeChild(rpc, creds,
                    "/srv/tenant/TV", "/srv/tenant/TV", "Historical"))
            assertEquals(0, fx.writes.get())
            assertEquals(0, fx.siblingCalls.get())
        }
    }

    @Test fun reconciliationWorksWithVerifiedFileManagerAncestorRoot() = runBlocking {
        Fixture().use { fx ->
            fx.configuredRoot = "/srv"
            fx.created.add("Old")
            val creds = RemoteCredentials(fx.rpc, "alice", "secret")
            assertEquals(RemoteFolderReadback.VERIFIED_DIRECTORY,
                RuTorrentFileManager.observeChild(java.net.URI(fx.rpc), creds,
                    "/srv/tenant", "/srv/tenant/TV", "Old"))
            assertEquals(RemoteFolderReadback.CANNOT_VERIFY,
                RuTorrentFileManager.observeChild(java.net.URI(fx.rpc), creds,
                    "/srv/tenant", "/srv/other", "Old"))
            assertEquals(0, fx.writes.get())
            assertEquals(0, fx.siblingCalls.get())
        }
    }

    @Test fun lostAcknowledgementNeverReplaysTheWrite() = runBlocking {
        Fixture().use { fx ->
            fx.ackStatus = 500
            val result = fx.remote().createServerFolder("/srv/tenant",
                "/srv/tenant/TV", "Lost-ack")
            assertEquals(RemoteFolderCreateOutcome.Created("/srv/tenant/TV/Lost-ack"), result)
            assertEquals(1, fx.writes.get())
        }
        Fixture().use { fx ->
            fx.applyWrite = false
            val result = fx.remote().createServerFolder("/srv/tenant",
                "/srv/tenant/TV", "No-ack")
            assertEquals(RemoteFolderCreateOutcome.Uncertain, result)
            assertEquals(1, fx.writes.get())
        }
    }
}
