package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * A reported server default is an opaque Unix path, not a user-entered
 * text field. Never trim it into a different reviewed Place. This is a
 * localhost read-only fixture, not hosted rTorrent acceptance.
 */
class RemoteDefaultDirectoryIdentityTest {
    private class Gateway(private val default: String) : AutoCloseable {
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val methods: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val endpoint: String get() = "http://127.0.0.1:${server.address.port}/RPC2"

        init {
            server.createContext("/RPC2") { exchange ->
                val request = exchange.requestBody.bufferedReader().use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(request)?.groupValues?.get(1).orEmpty()
                methods += method
                require(method == "directory.default") { "No mutation is authorized" }
                val xml = ("<methodResponse><params><param><value><string>" +
                    default + "</string></value></param></params></methodResponse>")
                    .toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, xml.size.toLong())
                exchange.responseBody.use { it.write(xml) }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    @Test fun legitimateTrailingSpacesRemainPartOfTheFrozenRemotePlace() = runBlocking {
        val observed = "/media/Classic TV "
        Gateway(observed).use { gateway ->
            val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
            val selected = requireNotNull(remote.defaultDirectory())
            assertEquals(observed, selected)
            val plan = StoppedRemoteAddPlan.fromMagnetAtServerDefault(
                "magnet:?xt=urn:btih:" + "ab".repeat(20), selected)
            assertEquals("Observed default must remain exact for later readback",
                observed, plan.destination)
            assertTrue(plan.useServerDefault)
            assertEquals(listOf("directory.default"), gateway.methods)
        }
    }

    @Test fun leadingWhitespaceOrControlCharactersCannotBecomeAnInventedDefault() = runBlocking {
        val bad = listOf(" /srv/downloads", "\t/srv/downloads",
            "/srv/downloads\n", "/srv/downloads\u007f")
        for (value in bad) {
            Gateway(value).use { gateway ->
                val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
                assertNull("Do not silently normalize an RPC-reported Place", remote.defaultDirectory())
                assertEquals(listOf("directory.default"), gateway.methods)
            }
        }
    }

    @Test fun conventionalTrailingSlashNormalizationRemainsCompatible() = runBlocking {
        Gateway("/srv/downloads/").use { gateway ->
            val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
            assertEquals("/srv/downloads", remote.defaultDirectory())
            assertEquals(listOf("directory.default"), gateway.methods)
        }
    }
}
