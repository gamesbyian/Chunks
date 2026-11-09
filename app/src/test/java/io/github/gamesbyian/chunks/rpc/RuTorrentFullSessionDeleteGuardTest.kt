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
        check: suspend (RTorrentRemote, AtomicInteger, AtomicInteger, AtomicInteger) -> Unit
    ) = runBlocking {
        val defaultReads = AtomicInteger()
        val hiddenFileReads = AtomicInteger()
        val pluginPosts = AtomicInteger()
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/rutorrent/plugins/httprpc/action.php") { exchange ->
            val request = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>")
                .find(request)?.groupValues?.get(1).orEmpty()
            val value = when (method) {
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
                "f.multicall" -> {
                    val t = if (request.contains("<string>" + hidden.hash + "</string>")) {
                        hiddenFileReads.incrementAndGet()
                        hidden
                    } else selected
                    array(listOf(array(listOf(string(t.file), number(100), number(1)))))
                }
                "d.hash" -> string(selected.hash)
                "d.name" -> string(selected.name)
                "d.directory" -> string(selected.directory)
                else -> throw AssertionError("Unexpected rTorrent RPC getter: " + method)
            }
            respond(exchange, value)
        }
        server.createContext("/rutorrent/plugins/erasedata/action.php") { exchange ->
            pluginPosts.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            val bytes = "[0,0,0]".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val endpoint = "http://127.0.0.1:" + server.address.port +
                "/rutorrent/plugins/httprpc/action.php"
            val remote = RTorrentRemote(RemoteCredentials(endpoint, "", ""))
            check(remote, defaultReads, hiddenFileReads, pluginPosts)
        } finally {
            server.stop(0)
        }
    }

    @Test fun hiddenJobSharingSelectedPayloadRefusesBeforeAnyPluginPost() =
        fixture(defaultFirst = listOf(selected, hidden)) {
                remote, defaultReads, hiddenFileReads, posts ->
            assertEquals(RemoteDeleteDataOutcome.Refused,
                remote.removeWithDataViaRuTorrent(selected.hash, selected.name, selected.directory))
            assertTrue("The default session, not just main, must be inspected",
                defaultReads.get() >= 1)
            assertEquals("The hidden same-file job must be examined", 1, hiddenFileReads.get())
            assertEquals("Shared remote payload must never be deleted", 0, posts.get())
        }

    @Test fun defaultSessionMissingMainJobRefusesDestructiveAction() =
        fixture(defaultFirst = emptyList()) { remote, defaultReads, _, posts ->
            assertEquals(RemoteDeleteDataOutcome.Refused,
                remote.removeWithDataViaRuTorrent(selected.hash, selected.name, selected.directory))
            assertEquals(1, defaultReads.get())
            assertEquals(0, posts.get())
        }

    @Test fun newlyRegisteredHiddenJobBeforePostInvalidatesInitialPreflight() =
        fixture(defaultFirst = listOf(selected),
            defaultLater = listOf(selected, hidden)) {
                remote, defaultReads, hiddenFileReads, posts ->
            assertEquals(RemoteDeleteDataOutcome.Refused,
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
                outcome is RemoteDeleteDataOutcome.Uncertain)
            assertEquals("Both session snapshots must be read", 2, defaultReads.get())
            assertEquals("Authorized non-idempotent plugin request must be sent once",
                1, posts.get())
        }
}
