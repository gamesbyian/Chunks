package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Malformed gateway peer-source data must never be published as verified connection details. */
class RemotePeerSourceIntegrityTest {
    private val hash = "a".repeat(40)
    private fun str(v: String) = "<string>$v</string>"
    private fun integer(v: Long) = "<i8>$v</i8>"
    private fun double(v: String) = "<double>$v</double>"
    private fun result(value: String) =
        "<methodResponse><params><param><value>$value</value></param></params></methodResponse>"

    private class Gateway(private val answer: (String) -> String) : AutoCloseable {
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val methods: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val endpoint get() = "http://127.0.0.1:${server.address.port}/RPC2"
        init {
            server.createContext("/RPC2") { exchange ->
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(body)?.groupValues?.get(1).orEmpty()
                methods.add(method)
                val bytes = answer(method).toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }
        override fun close() = server.stop(0)
    }

    private fun fetch(port: String, privacy: String, active: String): Pair<RemotePeerSource, List<String>> {
        Gateway { method ->
            result(when (method) {
                "d.hash" -> str("a".repeat(40))
                "network.local_address" -> str("127.0.0.1")
                "network.bind_address" -> str("0.0.0.0")
                "network.listen.port" -> port
                "d.is_private" -> privacy
                "d.is_active" -> active
                else -> error("Unexpected RPC: $method")
            })
        }.use { gateway ->
            val source = runBlocking {
                RTorrentRemote(RemoteCredentials(gateway.endpoint, "", "")).peerSource("a".repeat(40))
            }
            return source to gateway.methods.toList()
        }
    }

    @Test fun legitimatePeerSourceHasExactTypedPortAndFlags() {
        val (source, methods) = fetch(integer(51413), integer(1), integer(0))
        assertEquals(51413L, source.listenPort)
        assertTrue(source.isPrivate)
        assertFalse(source.isActive)
        assertEquals("127.0.0.1", source.rpcHost)
        assertNull("Unusable wildcard bind must stay unknown", source.boundAddress)
        assertEquals(listOf("d.hash", "network.local_address", "network.bind_address",
            "network.listen.port", "d.is_private", "d.is_active", "d.hash"), methods)
    }

    @Test fun invalidPeerPortsCannotBePublished() {
        val invalidPorts = listOf(
            integer(-1), integer(0), integer(65536), integer(Long.MAX_VALUE),
            double("51413.5"), double("NaN")
        )
        for (port in invalidPorts) {
            Gateway { method -> result(when (method) {
                "d.hash" -> str("a".repeat(40))
                "network.local_address" -> str("example.org")
                "network.bind_address" -> str("0.0.0.0")
                "network.listen.port" -> port
                else -> error("No further getters allowed after invalid port: $method")
            }) }.use { gateway ->
                try {
                    runBlocking {
                        RTorrentRemote(RemoteCredentials(gateway.endpoint, "", "")).peerSource(hash)
                    }
                    fail("Invalid peer port was accepted: $port")
                } catch (_: IllegalArgumentException) {
                    assertEquals(listOf("d.hash", "network.local_address", "network.bind_address",
                        "network.listen.port"), gateway.methods)
                }
            }
        }
    }

    @Test fun malformedPrivacyOrActivityCannotBecomeTrueOrFalse() {
        for (methodToBreak in listOf("d.is_private", "d.is_active")) {
            for (badFlag in listOf(integer(-1), integer(2), double("0.5"), double("1.0"))) {
                Gateway { method ->
                    result(when (method) {
                        "d.hash" -> str("a".repeat(40))
                        "network.local_address" -> str("127.0.0.1")
                        "network.bind_address" -> str("0.0.0.0")
                        "network.listen.port" -> integer(51413)
                        "d.is_private" -> if (methodToBreak == method) badFlag else integer(0)
                        "d.is_active" -> if (methodToBreak == method) badFlag else integer(1)
                        else -> error("Unexpected mutation: $method")
                    })
                }.use { gateway ->
                    try {
                        runBlocking {
                            RTorrentRemote(RemoteCredentials(gateway.endpoint, "", "")).peerSource(hash)
                        }
                        fail("Malformed $methodToBreak flag accepted: $badFlag")
                    } catch (_: IllegalArgumentException) {
                        assertFalse(gateway.methods.any {
                            it !in setOf("d.hash", "network.local_address", "network.bind_address",
                                "network.listen.port", "d.is_private", "d.is_active")
                        })
                        assertEquals(1, gateway.methods.count { it == methodToBreak })
                    }
                }
            }
        }
    }
    @Test fun vanishedOrRetargetedHashNeverBecomesTrustedPeerSource() {
        for (failBefore in listOf(true, false)) {
            var checks = 0
            Gateway { method ->
                result(when (method) {
                    "d.hash" -> {
                        checks++
                        if (failBefore || checks == 2) str("b".repeat(40))
                        else str("a".repeat(40))
                    }
                    "network.local_address" -> str("127.0.0.1")
                    "network.bind_address" -> str("0.0.0.0")
                    "network.listen.port" -> integer(51413)
                    "d.is_private" -> integer(0)
                    "d.is_active" -> integer(1)
                    else -> error("Unexpected mutation: $method")
                })
            }.use { gateway ->
                try {
                    runBlocking {
                        RTorrentRemote(RemoteCredentials(gateway.endpoint, "", "")).peerSource(hash)
                    }
                    fail("Mismatched exact hash was accepted")
                } catch (_: IllegalStateException) {
                    assertEquals(if (failBefore) 1 else 2,
                        gateway.methods.count { it == "d.hash" })
                    if (failBefore) assertEquals(listOf("d.hash"), gateway.methods)
                    else assertEquals("The identity changed after field reads",
                        "d.hash", gateway.methods.last())
                }
            }
        }
    }

}
