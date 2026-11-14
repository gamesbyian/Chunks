package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Exact ruTorrent HTTP/XML-RPC integration fixture. These test the real
 * RTorrentRemote preflight and the count of destructive plugin POSTs, never
 * a protected account or actual payload deletion.
 */
class RuTorrentFullSessionDeleteGuardTest {
    private data class Torrent(
        val hash: String, val name: String, val directory: String,
        val file: String
    )

    private var batchReads = 0

    private val selected = Torrent("ab".repeat(20), "Selected", "/downloads", "shared.bin")
    private val hidden = Torrent("cd".repeat(20), "Hidden", "/downloads", "shared.bin")

    private fun string(value: String) = "<value><string>" + value + "</string></value>"
    private fun number(value: Long) = "<value><i8>" + value + "</i8></value>"
    private fun array(items: List<String>) =
        "<value><array><data>" + items.joinToString("") + "</data></array></value>"

    private fun torrentRow(t: Torrent): String = array(listOf(
        string(t.hash), string(t.name), number(100), number(0),
        number(0), string(t.directory), number(0), number(0), number(0)
    ))

    private fun respond(exchange: HttpExchange, value: String) {
        val xml = ("<methodResponse><params><param>" + value +
            "</param></params></methodResponse>").toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(200, xml.size.toLong())
        exchange.responseBody.use { it.write(xml) }
    }

    private fun fixture(
        defaultFirst: List<Torrent>,
        defaultLater: List<Torrent> = defaultFirst,
        mount: String = "/rutorrent",
        invisibleDefaultJobs: Long = 0,
        pluginStatus: Int = 200,
        denyRead: String? = null,
        denyReadAfter: Int = 1,
        legacy: Boolean = false,
        legacyPrincipal: String = "alice",
        incompleteHooks: Boolean = false,
        advertiseAdapter: Boolean = true,
        pluginHeadStatus: Int = 200,
        check: suspend (RTorrentRemote, AtomicInteger, AtomicInteger, AtomicInteger) -> Unit
    ) = runBlocking {
        val erased = java.util.concurrent.atomic.AtomicBoolean(false)
        val deniedReads = AtomicInteger()
        val defaultReads = AtomicInteger()
        val hiddenCandidate = (defaultFirst + defaultLater).firstOrNull {
            it.hash.equals(hidden.hash, true)
        } ?: hidden
        val hiddenFileReads = AtomicInteger()
        val pluginPosts = AtomicInteger()
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("$mount/plugins/httprpc/action.php") { exchange ->
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
                return@createContext
            }
            val request = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>")
                .find(request)?.groupValues?.get(1).orEmpty()
            if (method == denyRead && deniedReads.incrementAndGet() >= denyReadAfter) {
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
                return@createContext
            }
            if (method == "d.hash" && erased.get()) {
                val bytes = "<methodResponse><fault><value><struct><member><name>faultCode</name><value><int>-501</int></value></member><member><name>faultString</name><value><string>Could not find info-hash.</string></value></member></struct></value></fault></methodResponse>".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                return@createContext
            }
            val value = when (method) {
                "system.client_version" -> string("0.9.8")
                "d.custom5" -> string("")
                "method.get" -> {
                    val collect = "d.open= ; branch=d.custom5=,f.multicall=,file.append=/profile/erasedata/,f.frozen_path="
                    val finish = "branch=d.custom5=,execute={/web/erasedata/fin.sh,system.pid=,d.base_path=,d.is_multi_file=}"
                    "<value><struct><member><name>erasedata0$legacyPrincipal</name>" + string(collect) + "</member>" +
                        (if (incompleteHooks) "" else "<member><name>erasedata1$legacyPrincipal</name>" + string(finish) + "</member>") +
                        "</struct></value>"
                }
                "view.size_not_visible" -> number(invisibleDefaultJobs)
                "d.multicall2" -> {
                    val rows = if (request.contains("<string>default</string>")) {
                        if (defaultReads.incrementAndGet() == 1) defaultFirst else defaultLater
                    } else {
                        assertTrue("Only the main/default rTorrent views may be read",
                            request.contains("<string>main</string>"))
                        listOf(selected)
                    }
                    array(rows.map(::torrentRow))
                }
                "system.multicall" -> {
                    if (request.contains("<string>d.custom5.set</string>")) {
                        pluginPosts.incrementAndGet()
                        erased.set(true)
                        respond(exchange, array(listOf(array(listOf(string("1"))), array(listOf(number(0))), array(listOf(number(0))))))
                        return@createContext
                    }
                    batchReads++
                    val jobs = (defaultFirst + defaultLater).associateBy { it.hash }
                    val results = Regex("<value><struct>(.*?)</struct></value>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(request).map { entry ->
                            val h = Regex("<string>([0-9a-fA-F]{40})</string>")
                                .find(entry.value)!!.groupValues[1]
                            val job = jobs.getValue(h)
                            val result = if (entry.value.contains("<string>d.hash</string>")) string(h)
                                else array(listOf(array(listOf(string(job.file)))))
                            array(listOf(result))
                        }.toList()
                    array(results)
                }
                "f.multicall" -> {
                    val t = if (request.contains("<string>" + hidden.hash + "</string>")) {
                        hiddenFileReads.incrementAndGet()
                        hiddenCandidate
                    } else selected
                    if (request.contains("f.frozen_path=")) array(listOf(array(listOf(string(t.file), string(t.directory + "/" + t.file)))))
                    else array(listOf(array(listOf(string(t.file), number(100), number(1)))))
                }
                "d.hash" -> string(selected.hash)
                "d.name" -> string(selected.name)
                "d.directory" -> string(selected.directory)
                else -> throw AssertionError("Unexpected rTorrent RPC getter: " + method)
            }
            respond(exchange, value)
        }
        server.createContext("$mount/plugins/erasedata/action.php") { exchange ->
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(if (legacy) 404 else pluginHeadStatus, -1)
                exchange.close()
                return@createContext
            }
            pluginPosts.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            val bytes = "[0,0,0]".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(pluginStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("$mount/plugins/erasedata/init.js") { exchange ->
            val bytes = (if (legacy) "rTorrentStub.prototype.removewithdata = function() { d.set_custom5; d.delete_tied; d.erase; }" else if (advertiseAdapter) "rTorrentStub.prototype.removewithdata = function() { mountPoint = 'plugins/erasedata/action.php'; content = 'mode=removewithdata'; }" else "<html>login</html>").toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val endpoint = "http://127.0.0.1:" + server.address.port +
                "$mount/plugins/httprpc/action.php"
            val remote = RTorrentRemote(RemoteCredentials(endpoint, if (legacy) "alice" else "", ""))
            check(remote, defaultReads, hiddenFileReads, pluginPosts)
        } finally {
            server.stop(0)
        }
    }

    @Test fun hostedNumberedTenantUsesOnlyItsOwnPluginForApprovedCleanup() =
        fixture(defaultFirst = listOf(selected), mount = "/tenant2/rutorrent3") {
                remote, defaultReads, _, posts ->
            val outcome = remote.removeWithDataViaRuTorrent(
                selected.hash, selected.name, selected.directory)
            assertTrue("Plugin ACK only queues cleanup, never proves deletion",
                outcome == RemoteDeleteDataOutcome.QueuedForCleanup(selected.hash, sessionRemoved = false))
            assertEquals("Both complete session views must still be checked", 2, defaultReads.get())
            assertEquals("Only the tenant2/rutorrent3 erase endpoint is available", 1, posts.get())
        }

    @Test fun hiddenJobSharingSelectedPayloadRefusesBeforeAnyPluginPost() =
        fixture(defaultFirst = listOf(selected, hidden)) {
                remote, defaultReads, hiddenFileReads, posts ->
            assertEquals(RemoteDeleteDataOutcome.Refused(RemoteDeleteRefusal.SHARED_FILES),
                remote.removeWithDataViaRuTorrent(selected.hash, selected.name, selected.directory))
            assertTrue("The default session, not just main, must be inspected",
                defaultReads.get() >= 1)
            assertEquals("The hidden same-file job must be examined", 1, hiddenFileReads.get())
            assertEquals("Shared remote payload must never be deleted", 0, posts.get())
        }

    @Test fun hiddenRootDirectoryFilesStillOverlapSelectedChildRoot() =
        fixture(defaultFirst = listOf(selected,
            hidden.copy(directory = "/", file = "downloads/shared.bin"))) {
                remote, _, hiddenFileReads, posts ->
            assertEquals(RemoteDeleteDataOutcome.Refused(RemoteDeleteRefusal.SHARED_FILES),
                remote.removeWithDataViaRuTorrent(selected.hash, selected.name, selected.directory))
            assertEquals("A root-owned file must be compared against selected paths",
                1, hiddenFileReads.get())
            assertEquals(0, posts.get())
        }

    @Test fun hiddenDotSegmentDirectoryCannotProveSafeNonOverlap() =
        fixture(defaultFirst = listOf(selected,
            hidden.copy(directory = "/other/../downloads"))) {
                remote, _, _, posts ->
            assertEquals(RemoteDeleteDataOutcome.Refused(RemoteDeleteRefusal.SHARED_FILES),
                remote.removeWithDataViaRuTorrent(selected.hash, selected.name, selected.directory))
            assertEquals(0, posts.get())
        }

    @Test fun defaultSessionMissingMainJobRefusesDestructiveAction() =
        fixture(defaultFirst = emptyList()) { remote, defaultReads, _, posts ->
            assertEquals(RemoteDeleteDataOutcome.Refused(RemoteDeleteRefusal.INCOMPLETE_SESSION),
                remote.removeWithDataViaRuTorrent(selected.hash, selected.name, selected.directory))
            assertEquals(1, defaultReads.get())
            assertEquals(0, posts.get())
        }

    @Test fun newlyRegisteredHiddenJobBeforePostInvalidatesInitialPreflight() =
        fixture(defaultFirst = listOf(selected),
            defaultLater = listOf(selected, hidden)) {
                remote, defaultReads, hiddenFileReads, posts ->
            assertEquals(RemoteDeleteDataOutcome.Refused(RemoteDeleteRefusal.SESSION_CHANGED),
                remote.removeWithDataViaRuTorrent(selected.hash, selected.name, selected.directory))
            assertEquals(2, defaultReads.get())
            // The changed membership itself forbids the POST. Reading the new
            // hidden file is optional only because deletion is already refused.
            assertTrue(hiddenFileReads.get() in 0..1)
            assertEquals(0, posts.get())
        }

    @Test fun stableUnsharedCompleteSessionKeepsTheSingleApprovedPostPath() =
        fixture(defaultFirst = listOf(selected)) { remote, defaultReads, _, posts ->
            val outcome = remote.removeWithDataViaRuTorrent(
                selected.hash, selected.name, selected.directory)
            assertTrue("A valid plugin receipt does not prove payload deletion",
                outcome == RemoteDeleteDataOutcome.QueuedForCleanup(selected.hash, sessionRemoved = false))
            assertEquals("Both session snapshots must be read", 2, defaultReads.get())
            assertEquals("Authorized non-idempotent plugin request must be sent once",
                1, posts.get())
        }
    /** Data-integrity: default is filterable in actual rTorrent; a partial view cannot authorize data deletion. */
    @Test fun aFilteredDefaultViewRefusesBeforeReadingPayloadsOrPosting() =
        fixture(defaultFirst = listOf(selected), invisibleDefaultJobs = 1) { remote, _, fileReads, posts ->
            assertEquals(RemoteDeleteDataOutcome.Refused(RemoteDeleteRefusal.INCOMPLETE_SESSION),
                remote.removeWithDataViaRuTorrent(selected.hash, selected.name, selected.directory))
            assertEquals(0, fileReads.get())
            assertEquals(0, posts.get())
        }

    /** Behavioral: an auth denial and missing plugin are actionable refusals, not uncertain cleanup. */
    @Test fun pluginAuthAndUnavailableRoutesHaveDistinctFixedDiagnostics() {
        for ((status, reason) in listOf(401 to RemoteDeleteRefusal.AUTHENTICATION,
                403 to RemoteDeleteRefusal.AUTHENTICATION, 404 to RemoteDeleteRefusal.PLUGIN_UNAVAILABLE,
                405 to RemoteDeleteRefusal.PLUGIN_METHOD_REFUSED)) {
            fixture(defaultFirst = listOf(selected), pluginStatus = status) { remote, _, _, posts ->
                assertEquals(RemoteDeleteDataOutcome.Refused(reason),
                    remote.removeWithDataViaRuTorrent(selected.hash, selected.name, selected.directory))
                assertEquals(1, posts.get())
            }
        }
    }

    /** Data-integrity/latency: fixed read-only batches preserve shared-file checks on larger accounts. */
    @Test fun overlappingSessionsAreBatchedAndAConflictStillPreventsTheOnlyPost() {
        val others = (1..55).map { n -> Torrent("%040x".format(n), "Other$n", "/downloads", "other$n.bin") }
        fixture(defaultFirst = listOf(selected) + others) { remote, _, _, posts ->
            assertEquals(RemoteDeleteDataOutcome.QueuedForCleanup(selected.hash, sessionRemoved = false),
                remote.removeWithDataViaRuTorrent(selected.hash, selected.name, selected.directory))
            assertEquals("55 jobs in three bounded pages, revalidated twice", 6, batchReads)
            assertEquals(1, posts.get())
        }
        batchReads = 0
        fixture(defaultFirst = listOf(selected) + others + hidden) { remote, _, _, posts ->
            assertEquals(RemoteDeleteDataOutcome.Refused(RemoteDeleteRefusal.SHARED_FILES),
                remote.removeWithDataViaRuTorrent(selected.hash, selected.name, selected.directory))
            assertEquals(0, posts.get())
        }
    }

    /** Data-integrity: expiry at any preflight stage remains an authentication refusal with no destructive request. */
    @Test fun authenticationExpiryDuringSharedFilesAndFinalIdentityNeverMasqueradesAsAChangedJob() {
        val others = listOf(hidden.copy(file = "different.bin"),
            hidden.copy(hash = "ef".repeat(20), file = "another.bin"))
        for ((method, after) in listOf("system.multicall" to 1, "d.hash" to 1, "f.multicall" to 1, "f.multicall" to 2)) {
            fixture(defaultFirst = listOf(selected) + others, denyRead = method, denyReadAfter = after) { remote, _, _, posts ->
                assertEquals(RemoteDeleteDataOutcome.Refused(RemoteDeleteRefusal.AUTHENTICATION),
                    remote.removeWithDataViaRuTorrent(selected.hash, selected.name, selected.directory))
                assertEquals(0, posts.get())
            }
        }
    }


    @Test fun actualAccountHooksAndBrowserSelectOneLegacyBatchInsteadOfMissingHttpEndpoint() =
        fixture(listOf(selected), legacy = true) { remote, _, _, posts ->
            val cap = remote.discoverDeleteDataCapability()
            assertEquals(RemoteDeleteAdapter.REGISTERED_ERASE_HOOKS, cap.adapter)
            assertEquals(RemoteDeleteDataOutcome.QueuedForCleanup(selected.hash),
                remote.removeWithDataViaRuTorrent(selected.hash, selected.name, selected.directory))
            assertEquals("One legacy batch; no fallback HTTP deletion", 1, posts.get())
        }

    @Test fun anotherPrincipalHooksCannotAuthorizeLegacyFlaggingOrErase() =
        fixture(listOf(selected), legacy = true, legacyPrincipal = "bob") { remote, _, _, posts ->
            assertFalse(remote.discoverDeleteDataCapability().actionable)
            assertEquals(RemoteDeleteDataOutcome.Refused(RemoteDeleteRefusal.CAPABILITY_UNKNOWN),
                remote.removeWithDataViaRuTorrent(selected.hash, selected.name, selected.directory))
            assertEquals(0, posts.get())
        }

    @Test fun incompleteLegacyHookRegistrationCannotAuthorizeDataRemoval() =
        fixture(listOf(selected), legacy = true, incompleteHooks = true) { remote, _, _, posts ->
            assertFalse(remote.discoverDeleteDataCapability().actionable)
            assertEquals(RemoteDeleteDataOutcome.Refused(RemoteDeleteRefusal.CAPABILITY_UNKNOWN),
                remote.removeWithDataViaRuTorrent(selected.hash, selected.name, selected.directory))
            assertEquals(0, posts.get())
        }

    @Test fun successfulHarmlessHttpDoesNotProveADeletionAdapter() =
        fixture(listOf(selected), advertiseAdapter = false) { remote, _, _, posts ->
            val cap = remote.discoverDeleteDataCapability()
            assertEquals(RemoteCapabilityState.UNKNOWN, cap.state)
            assertFalse(cap.actionable)
            assertEquals(0, posts.get())
        }
    @Test fun phpEndpointRequiringPostCanBeDiscoveredFromItsGenuineBrowserAdapter() =
        fixture(listOf(selected), pluginHeadStatus = 500) { remote, _, _, posts ->
            val cap = remote.discoverDeleteDataCapability()
            assertTrue(cap.reason, cap.actionable)
            assertEquals(RemoteDeleteAdapter.ERASEDATA_HTTP, cap.adapter)
            assertEquals(0, posts.get())
        }
}
