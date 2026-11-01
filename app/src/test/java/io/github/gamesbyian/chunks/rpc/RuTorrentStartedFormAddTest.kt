package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exact-host ruTorrent server-side Add form: same endpoint for files and magnets. */
class RuTorrentStartedFormAddTest {
    private val torrent = ("d4:infod6:lengthi5e4:name8:test.txt12:piece lengthi16384e6:pieces20:" +
        "01234567890123456789ee").toByteArray(Charsets.ISO_8859_1)
    private val hash = TorrentMetainfoReader.preview(torrent).infoHashV1
    private val magnetHash = "ab".repeat(20)
    private val magnet = "magnet:?xt=urn:btih:$magnetHash&dn=FormFixture&tr=http%3A%2F%2Fexample.invalid%2Fannounce"
    private fun reply(value: String) =
        "<methodResponse><params><param><value>$value</value></param></params></methodResponse>"
    private fun absent() = """<methodResponse><fault><value><struct>
        <member><name>faultCode</name><value><int>-501</int></value></member>
        <member><name>faultString</name><value><string>Could not find info-hash.</string></value></member>
        </struct></value></fault></methodResponse>"""

    private inner class Host(val status: Int = 200, val activate: Boolean = true) : AutoCloseable {
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val posts: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val rpc: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var loaded = false
        @Volatile var selectedHash = ""
        @Volatile var selectedName = ""
        val root get() = "http://127.0.0.1:${server.address.port}/rutorrent/"
        fun start(hash: String, name: String) {
            selectedHash = hash
            selectedName = name
            server.createContext("/rutorrent/php/addtorrent.php") { exchange ->
                val body = String(exchange.requestBody.readBytes(), Charsets.ISO_8859_1)
                posts += body
                // A server can register a transfer before losing its form ACK.
                loaded = true
                val b = "{}".toByteArray()
                exchange.sendResponseHeaders(status, if (status == 200) b.size.toLong() else -1)
                if (status == 200) exchange.responseBody.use { it.write(b) } else exchange.close()
            }
            server.createContext("/rutorrent/plugins/httprpc/action.php") { exchange ->
                val input = exchange.requestBody.bufferedReader().use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(input)?.groupValues?.get(1).orEmpty()
                rpc += method
                val result = when (method) {
                    "d.multicall2" -> reply("<array><data></data></array>")
                    "d.name" -> if (loaded) reply("<string>$name</string>") else absent()
                    "d.hash" -> reply("<string>$hash</string>")
                    "d.directory" -> reply("<string>/downloads</string>")
                    "d.is_multi_file" -> reply("<i8>0</i8>")
                    "d.state" -> reply("<i8>1</i8>")
                    "d.is_active" -> reply("<i8>${if (activate) 1 else 0}</i8>")
                    else -> error("Unexpected RPC $method")
                }
                val b = result.toByteArray()
                exchange.sendResponseHeaders(200, b.size.toLong())
                exchange.responseBody.use { it.write(b) }
            }
            server.start()
        }
        override fun close() = server.stop(0)
    }

    @Test fun selectedTorrentGoesThroughRuTorrentPhpAsOneStartedUpload() = runBlocking {
        Host().use { host ->
            host.start(hash, "test.txt")
            val remote = RTorrentRemote(RemoteCredentials(host.root, "", ""))
            val plan = StoppedRemoteAddPlan.fromMagnet("magnet:?xt=urn:btih:$hash", "/downloads")
            assertEquals(RemoteAddAndStart.Started(hash),
                remote.loadStartedViaRuTorrentForm(plan, torrent))
            val request = host.posts.single()
            assertTrue(request.contains("name=\"dir_edit\"\r\n\r\n/downloads"))
            assertTrue(request.contains("name=\"torrent_file\""))
            assertTrue(request.contains(String(torrent, Charsets.ISO_8859_1)))
            assertFalse("Server form must default to Start", request.contains("torrents_start_stopped"))
            assertEquals(2, host.rpc.count { it == "d.hash" })
            assertFalse(host.rpc.any { it.startsWith("load.") || it == "d.start" })
            assertEquals(RemoteAddAndStart.AddedStopped(RemoteAddOutcome.AlreadyPresent(hash)),
                remote.loadStartedViaRuTorrentForm(plan, torrent))
            assertEquals(1, host.posts.size)
        }
    }

    @Test fun magnetUsesRuTorrentUrlFieldWithoutSendingMetainfoBytes() = runBlocking {
        Host().use { host ->
            host.start(magnetHash, "FormFixture")
            val remote = RTorrentRemote(RemoteCredentials(host.root, "", ""))
            val plan = StoppedRemoteAddPlan.fromMagnet(magnet, "/downloads")
            assertEquals(RemoteAddAndStart.Started(magnetHash),
                remote.loadStartedViaRuTorrentForm(plan))
            val form = host.posts.single()
            assertTrue(form.contains("name=\"url\"\r\n\r\n$magnet\r\n"))
            assertFalse(form.contains("torrent_file"))
            assertFalse(form.contains("torrents_start_stopped"))
        }
    }

    @Test fun startedFlagWithoutActiveEngineIsStillUnverifiedAndNeverRetried() = runBlocking {
        Host(activate = false).use { host ->
            host.start(magnetHash, "FormFixture")
            val remote = RTorrentRemote(RemoteCredentials(host.root, "", ""))
            val result = remote.loadStartedViaRuTorrentForm(
                StoppedRemoteAddPlan.fromMagnet(magnet, "/downloads"))
            assertTrue(result is RemoteAddAndStart.StartNotVerified)
            assertEquals(1, host.posts.size)
            assertFalse(host.rpc.any { it == "d.start" || it.startsWith("load.") })
        }
    }

    @Test fun lostHtmlFormAcknowledgementIsUncertainAndNoOtherTransportIsAttempted() = runBlocking {
        Host(status = 503).use { host ->
            host.start(magnetHash, "FormFixture")
            val remote = RTorrentRemote(RemoteCredentials(host.root, "", ""))
            assertEquals(RemoteAddAndStart.StartNotVerified(magnetHash, null),
                remote.loadStartedViaRuTorrentForm(
                    StoppedRemoteAddPlan.fromMagnet(magnet, "/downloads")))
            assertEquals(1, host.posts.size)
            assertFalse(host.rpc.any { it == "d.start" || it.startsWith("load.") })
        }
    }

    @Test fun unsupportedDirectRpcEndpointDoesNotInventFormPath() = runBlocking {
        val remote = RTorrentRemote(RemoteCredentials("http://127.0.0.1:7/RPC2", "", ""))
        assertNull(remote.ruTorrentUploadAddress())
        // The UI does not offer this alternative for direct-SCGI HTTP mounts.
    }

    @Test fun mismatchedSelectedBytesAreRejectedBeforeAnyUpload() = runBlocking {
        Host().use { host ->
            host.start(hash, "test.txt")
            val remote = RTorrentRemote(RemoteCredentials(host.root, "", ""))
            val plan = StoppedRemoteAddPlan.fromMagnet(magnet, "/downloads")
            try {
                remote.loadStartedViaRuTorrentForm(plan, torrent)
                fail("Expected source identity mismatch")
            } catch (_: IllegalStateException) {
                assertEquals(0, host.posts.size)
            }
        }
    }
}
