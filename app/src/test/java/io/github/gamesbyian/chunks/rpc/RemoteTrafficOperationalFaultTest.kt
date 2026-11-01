package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * A sampled exact-hash byte counter must not disguise authentication or
 * backend failure as an unsupported optional statistic. Disposable localhost
 * XML-RPC only. No real account, peer bytes or mutation command.
 */
class RemoteTrafficOperationalFaultTest {
    private val hash = "ab".repeat(20)

    private fun value(xml: String) =
        "<methodResponse><params><param><value>$xml</value></param></params></methodResponse>"
    private fun failure(code: Int, text: String) = """<methodResponse><fault><value><struct>
        <member><name>faultCode</name><value><int>$code</int></value></member>
        <member><name>faultString</name><value><string>$text</string></value></member>
        </struct></value></fault></methodResponse>"""

    private inner class Gateway(private val respond: (String) -> Pair<Int, String>) : AutoCloseable {
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val methods: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val endpoint: String get() = "http://127.0.0.1:${server.address.port}/RPC2"

        init {
            server.createContext("/RPC2") { exchange ->
                val raw = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(raw)?.groupValues?.get(1).orEmpty()
                methods.add(method)
                val (status, body) = respond(method)
                if (status != 200) {
                    exchange.sendResponseHeaders(status, -1)
                    exchange.close()
                } else {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    @Test fun expiredLoginAndUnavailableGatewayDoNotBecomeUnknownByteCounters() {
        for (status in listOf(401, 503)) {
            Gateway { method ->
                when (method) {
                    "d.hash" -> 200 to value("<string>$hash</string>")
                    "d.down.total" -> status to ""
                    else -> error("Do not read $method after HTTP $status")
                }
            }.use { gateway ->
                val exception = assertThrows(RpcHttpStatusException::class.java) {
                    runBlocking {
                        RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
                            .readTrafficSample(hash)
                    }
                }
                assertEquals(status, exception.status)
                assertEquals(listOf("d.hash", "d.down.total"), gateway.methods)
            }
        }
    }

    @Test fun operationalXmlRpcFaultsAbortAfterFirstCounter() {
        for ((code, message) in listOf(-503 to "Server busy", -403 to "Permission denied",
            -501 to "Could not find info-hash.")) {
            Gateway { method ->
                when (method) {
                    "d.hash" -> 200 to value("<string>$hash</string>")
                    "d.down.total" -> 200 to failure(code, message)
                    else -> error("Do not read $method after XML-RPC fault")
                }
            }.use { gateway ->
                val exception = assertThrows(RpcFault::class.java) {
                    runBlocking {
                        RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
                            .readTrafficSample(hash)
                    }
                }
                assertEquals(code.toString(), exception.code)
                assertEquals(listOf("d.hash", "d.down.total"), gateway.methods)
            }
        }
    }

    @Test fun genuinelyUnsupportedCounterRemainsUnknownWithoutFakingZero() = runBlocking {
        Gateway { method ->
            200 to when (method) {
                "d.hash" -> value("<string>$hash</string>")
                "d.down.total" -> failure(-32601, "Method d.down.total not found")
                "d.completed_bytes" -> value("<i8>120</i8>")
                "d.size_bytes" -> value("<i8>200</i8>")
                "d.is_active" -> value("<i8>0</i8>")
                "d.peers_connected" -> value("<i8>0</i8>")
                "d.down.rate" -> value("<i8>0</i8>")
                else -> error("Unexpected method $method")
            }
        }.use { gateway ->
            val report = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
                .readTrafficSample(hash)
            assertNull(report.receivedBytes)
            assertEquals(120L, report.verifiedBytes)
            assertEquals(false, report.active)
            assertEquals(listOf("d.hash", "d.down.total", "d.completed_bytes",
                "d.size_bytes", "d.is_active", "d.peers_connected", "d.down.rate",
                "d.hash"), gateway.methods)
        }
    }

    @Test fun nonBooleanActivityFlagCannotProduceAValidPeerByteSample() {
        Gateway { method ->
            200 to when (method) {
                "d.hash" -> value("<string>$hash</string>")
                "d.down.total", "d.completed_bytes" -> value("<i8>10</i8>")
                "d.size_bytes" -> value("<i8>100</i8>")
                "d.is_active" -> value("<i8>2</i8>")
                else -> error("Invalid activity must stop before $method")
            }
        }.use { gateway ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
                        .readTrafficSample(hash)
                }
            }
            assertEquals(listOf("d.hash", "d.down.total", "d.completed_bytes",
                "d.size_bytes", "d.is_active"), gateway.methods)
        }
    }
}
