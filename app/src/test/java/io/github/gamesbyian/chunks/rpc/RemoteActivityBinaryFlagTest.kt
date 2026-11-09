package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Details activity flags from a real loopback HTTP/XML-RPC exchange.
 * Pure read-only contract evidence; no hosted account or mutation.
 */
class RemoteActivityBinaryFlagTest {
    private val hash = "ab".repeat(20)

    private class Gateway(
        private val activeFlag: String = "<int>1</int>",
        private val openFlag: String = "<int>0</int>"
    ) : AutoCloseable {
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val methods: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val endpoint: String get() = "http://127.0.0.1:${server.address.port}/RPC2"

        init {
            server.createContext("/RPC2") { exchange ->
                val request = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(request)?.groupValues?.get(1).orEmpty()
                methods.add(method)
                val field = when (method) {
                    "d.is_active" -> activeFlag
                    "d.is_open" -> openFlag
                    "d.peers_connected" -> "<i8>3</i8>"
                    "d.down.rate" -> "<int>64</int>"
                    else -> error("Unexpected XML-RPC request")
                }
                val response = ("<methodResponse><params><param><value>" +
                    field + "</value></param></params></methodResponse>")
                    .toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    @Test fun zeroAndOneRemainValidWithNormalCounters() = runBlocking {
        Gateway(activeFlag = "<i8>0</i8>", openFlag = "<int>1</int>").use { gateway ->
            val activity = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
                .readActivity(hash)
            assertEquals(false, activity.active)
            assertEquals(true, activity.open)
            assertEquals(3L, activity.connectedPeers)
            assertEquals(64L, activity.downloadBytesPerSecond)
            assertEquals(listOf("d.is_active", "d.is_open", "d.peers_connected", "d.down.rate"),
                gateway.methods)
        }
    }

    @Test fun malformedActiveFlagCannotBecomeTrueOrUnknown() = runBlocking {
        for (bad in listOf("<int>2</int>", "<i8>-1</i8>", "<double>1.0</double>")) {
            Gateway(activeFlag = bad).use { gateway ->
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking {
                        RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
                            .readActivity(hash)
                    }
                }
                assertEquals(listOf("d.is_active"), gateway.methods)
            }
        }
    }

    @Test fun malformedOpenFlagStopsBeforeTrafficCounters() = runBlocking {
        for (bad in listOf("<int>2</int>", "<i8>-1</i8>", "<string>1</string>")) {
            Gateway(openFlag = bad).use { gateway ->
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking {
                        RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
                            .readActivity(hash)
                    }
                }
                assertEquals(listOf("d.is_active", "d.is_open"), gateway.methods)
                assertTrue(gateway.methods.none { it == "d.peers_connected" || it == "d.down.rate" })
            }
        }
    }
}
