package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuTorrentSourceMetainfoTest {
    private val info = "d6:lengthi5e4:name8:test.txt12:piece lengthi16384e6:pieces20:" +
        "01234567890123456789e"
    private val torrent = ("d8:announce14:http://ignored4:info" + info + "e")
        .toByteArray(Charsets.UTF_8)
    private val hash = TorrentMetainfoReader.preview(torrent).infoHashV1

    @Test fun sourceEndpointPreservesExactNumberedTenantAndRejectsOtherTransports() {
        val rpc = URI("https://example.invalid/owner/rutorrent3/plugins/httprpc/action.php")
        assertEquals("https://example.invalid/owner/rutorrent3/plugins/source/action.php",
            RuTorrentSourceMetainfo.endpoint(rpc)?.toString())
        assertNull(RuTorrentSourceMetainfo.endpoint(
            URI("https://example.invalid/owner/RPC2")))
        assertNull(RuTorrentSourceMetainfo.endpoint(
            URI("https://example.invalid/owner/rutorrent2/plugins/source/action.php")))
        assertNull(RuTorrentSourceMetainfo.endpoint(
            URI("https://example.invalid/owner/rutorrent2/plugins/httprpc/action.php?route=3")))
    }

    private fun serve(
        handler: (com.sun.net.httpserver.HttpExchange) -> Unit,
        exercise: suspend (URI) -> Unit
    ) = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pool = Executors.newSingleThreadExecutor()
        server.executor = pool
        server.createContext("/owner/rutorrent2/plugins/source/action.php", handler)
        server.start()
        try {
            val uri = URI("http://127.0.0.1:${server.address.port}/owner/rutorrent2/plugins/httprpc/action.php")
            exercise(uri)
        } finally {
            server.stop(0)
            pool.shutdownNow()
        }
    }

    private fun creds(uri: URI) = RemoteCredentials(uri.toString(), "owner", "secret")

    @Test fun challengedReadReturnsOnlyExactHashValidatedOriginal() = serve({ exchange ->
        assertEquals("POST", exchange.requestMethod)
        assertTrue(exchange.requestURI.path.endsWith("/rutorrent2/plugins/source/action.php"))
        assertEquals("hash=$hash", exchange.requestBody.readBytes().toString(Charsets.UTF_8))
        val authorized = exchange.requestHeaders.getFirst("Authorization") ==
            "Basic " + java.util.Base64.getEncoder()
                .encodeToString("owner:secret".toByteArray(Charsets.UTF_8))
        if (!authorized) {
            exchange.responseHeaders.set("WWW-Authenticate", "Basic realm=\"source\"")
            exchange.sendResponseHeaders(401, -1)
        } else {
            exchange.responseHeaders.set("Content-Type", "application/x-bittorrent")
            exchange.sendResponseHeaders(200, torrent.size.toLong())
            exchange.responseBody.use { it.write(torrent) }
        }
        exchange.close()
    }, { uri ->
        assertArrayEquals(torrent, RuTorrentSourceMetainfo.read(uri, creds(uri), hash))
    })

    @Test fun absentPluginRedirectWrongHashAndHtmlAreNeverRecoverable() {
        for (case in listOf(302, 404, 200)) {
            serve({ exchange ->
                val payload = if (case == 200) "<html>login</html>".toByteArray() else ByteArray(0)
                if (case == 302) exchange.responseHeaders.add("Location", "/other-account/login")
                exchange.sendResponseHeaders(case, if (payload.isNotEmpty()) payload.size.toLong() else -1)
                if (payload.isNotEmpty()) exchange.responseBody.use { it.write(payload) }
                exchange.close()
            }, { uri ->
                assertNull(RuTorrentSourceMetainfo.read(uri, creds(uri), hash))
            })
        }
        serve({ exchange ->
            exchange.sendResponseHeaders(200, torrent.size.toLong())
            exchange.responseBody.use { it.write(torrent) }
            exchange.close()
        }, { uri ->
            assertNull(RuTorrentSourceMetainfo.read(uri, creds(uri), "f".repeat(40)))
            assertNull(RuTorrentSourceMetainfo.read(uri, creds(uri), "bad-hash"))
        })
    }

    @Test fun oversizedSourceBodyCannotEscapeTheTwoMibMetadataLimit() = serve({ exchange ->
        exchange.sendResponseHeaders(200, (TorrentMetainfoReader.MAX_BYTES + 1).toLong())
        exchange.responseBody.use { it.write(ByteArray(TorrentMetainfoReader.MAX_BYTES + 1)) }
        exchange.close()
    }, { uri ->
        assertNull(RuTorrentSourceMetainfo.read(uri, creds(uri), hash))
    })
}
