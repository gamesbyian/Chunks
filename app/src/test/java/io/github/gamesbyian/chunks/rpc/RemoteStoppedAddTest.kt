package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteStoppedAddTest {
    private val hash = "ab".repeat(20)
    private val magnet = "magnet:?xt=urn:btih:$hash&dn=Open%20test"
    private val dir = "/home/dev/Chunks Test"

    @Test fun validatesMagnetAndArchiveSourceAndExplicitDirectory() {
        val m = StoppedRemoteAddPlan.fromMagnet(magnet, dir)
        assertEquals(hash, m.expectedInfoHash)
        assertEquals(dir, m.destination)
        val a = StoppedRemoteAddPlan.fromArchiveTorrent(
            "https://archive.org/download/example/example_archive.torrent", hash, dir)
        assertEquals(StoppedRemoteAddPlan.Kind.ARCHIVE_TORRENT_URL, a.kind)
        for (path in listOf("relative", "/", "/tmp/../media", "/tmp/,d.erase=", "/tmp//hidden")) {
            try {
                StoppedRemoteAddPlan.fromMagnet(magnet, path)
                fail("Must reject path: $path")
            } catch (_: IllegalArgumentException) { }
        }
        for (bad in listOf("https://evil.test/example.torrent",
                           "http://archive.org/download/x/x.torrent",
                           "https://archive.org/download/x/a.torrent?token=secret")) {
            try {
                StoppedRemoteAddPlan.fromArchiveTorrent(bad, hash, dir)
                fail("Must reject archive URL")
            } catch (_: IllegalArgumentException) { }
        }
    }

    private fun value(text: String) = "<value><string>$text</string></value>"
    private fun num(i: Long) = "<value><i8>$i</i8></value>"
    private fun rowXml(): String {
        val row = listOf(value(hash), value("Open test"), num(10), num(0),
                         num(0), value(dir), num(0), num(0), num(0))
        return "<value><array><data><value><array><data>" +
            row.joinToString("") + "</data></array></value></data></array></value>"
    }
    @Test fun loadsStoppedAndVerifiesDestinationAndState() = runBlocking {
        var loaded = false
        val requests = mutableListOf<String>()
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(body)?.groupValues?.get(1).orEmpty()
            requests.add(method + "|" + body)
            val responseValue = when (method) {
                "d.multicall2" -> if (loaded) rowXml()
                                   else "<value><array><data></data></array></value>"
                "load.verbose" -> {
                    if (!body.contains("d.directory_base.set=$dir") ||
                        !body.contains("magnet:?xt=")) throw AssertionError("Unexpected load request")
                    loaded = true
                    num(0)
                }
                "d.directory_base" -> value(dir)
                "d.state" -> num(0)
                else -> throw AssertionError("Unsupported test RPC: $method")
            }
            val xml = "<methodResponse><params><param>$responseValue</param></params></methodResponse>"
                .toByteArray()
            exchange.sendResponseHeaders(200, xml.size.toLong())
            exchange.responseBody.use { it.write(xml) }
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            val plan = StoppedRemoteAddPlan.fromMagnet(magnet, dir)
            assertEquals(RemoteAddOutcome.VerifiedStopped(hash, dir), remote.loadStopped(plan))
            assertTrue(loaded)
            assertEquals(listOf("d.multicall2", "load.verbose", "d.multicall2",
                "d.directory_base", "d.state"),
                requests.map { it.substringBefore('|') })
            val beforeDuplicate = requests.size
            assertEquals(RemoteAddOutcome.AlreadyPresent(hash), remote.loadStopped(plan))
            assertEquals(beforeDuplicate + 1, requests.size)
            assertEquals("d.multicall2", requests.last().substringBefore('|'))
        } finally {
            server.stop(0)
        }
    }
}
