package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Real loopback XML-RPC wire, read-only methods and exact transfer identity.
 * No hosted rTorrent account, actual Stop, Start or throttle mutation.
 */
class RemoteSeedingEvidenceRpcTest {
    private val hash = "ab".repeat(20)
    private fun value(inner: String) =
        "<methodResponse><params><param><value>$inner</value></param></params></methodResponse>"
    private fun number(value: Long) = value("<i8>$value</i8>")
    private fun string(value: String) = value("<string>$value</string>")
    private fun fault(code: Int, message: String) =
        "<methodResponse><fault><value><struct>" +
            "<member><name>faultCode</name><value><int>$code</int></value></member>" +
            "<member><name>faultString</name><value><string>$message</string></value></member>" +
            "</struct></value></fault></methodResponse>"

    private inner class Gateway(
        private val answer: (String) -> Pair<Int, String>
    ) : AutoCloseable {
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val methods: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val endpoint get() = "http://127.0.0.1:${server.address.port}/RPC2"
        init {
            server.createContext("/RPC2") { exchange ->
                try {
                    require(exchange.requestMethod == "POST") {
                        "Read-only getters must never use a write authentication preflight"
                    }
                    val xml = exchange.requestBody.bufferedReader().use { it.readText() }
                    val method = Regex("<methodName>([^<]+)</methodName>")
                        .find(xml)?.groupValues?.get(1).orEmpty()
                    methods.add(method)
                    val (status, body) = answer(method)
                    if (status != 200) {
                        exchange.sendResponseHeaders(status, -1)
                        exchange.close()
                    } else {
                        val bytes = body.toByteArray()
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                } finally { exchange.close() }
            }
            server.start()
        }
        fun client() = RTorrentRemote(RemoteCredentials(endpoint, "", ""))
        override fun close() = server.stop(0)
    }

    @Test fun readsVerifiableSeedingFactsWithNoCommandAuthority() = runBlocking {
        Gateway { method -> 200 to when (method) {
            "d.hash" -> string(hash.uppercase())
            "d.up.total" -> number(2_048)
            "d.down.total" -> number(1_024)
            "d.complete" -> number(1)
            "d.is_active" -> number(1)
            "d.timestamp.finished" -> number(1_720_000_000)
            "d.throttle_name" -> string("ipt-preset")
            else -> error("Never send mutation or unknown RPC $method")
        }}.use { gateway ->
            val report = gateway.client().readSeedingEvidence(hash)
            assertEquals(hash, report.infoHashV1)
            assertEquals(2_048L, report.uploadedBytes)
            assertEquals(1_024L, report.downloadedBytes)
            assertEquals(true, report.complete)
            assertEquals(true, report.active)
            assertEquals(1_720_000_000L, report.finishedAtEpochSeconds)
            assertEquals("ipt-preset", report.throttleName)
            assertFalse("Private hash/throttle must not leak from data class",
                report.toString().contains(hash))
            assertFalse(report.toString().contains("ipt-preset"))
            assertEquals(listOf(
                "d.hash", "d.up.total", "d.down.total", "d.complete",
                "d.is_active", "d.timestamp.finished", "d.throttle_name", "d.hash"
            ), gateway.methods)
        }
    }

    @Test fun unsupportedOptionalGettersRemainUnknownNotZeroOrUnlimited() = runBlocking {
        Gateway { method -> 200 to when (method) {
            "d.hash" -> string(hash)
            "d.up.total", "d.timestamp.finished", "d.throttle_name" ->
                fault(-32601, "Method $method not found")
            "d.down.total" -> number(1_024)
            "d.complete" -> number(1)
            "d.is_active" -> number(0)
            else -> error("Unexpected RPC $method")
        }}.use { gateway ->
            val report = gateway.client().readSeedingEvidence(hash)
            assertNull(report.uploadedBytes)
            assertNull(report.finishedAtEpochSeconds)
            assertNull(report.throttleName)
            assertEquals(1_024L, report.downloadedBytes)
            assertEquals(false, report.active)
            assertEquals(2, gateway.methods.count { it == "d.hash" })
        }
    }

    @Test fun operationalFailureStopsImmediatelyWithoutMoreCredentialedReads() {
        for (status in listOf(401, 503)) {
            Gateway { method -> when (method) {
                "d.hash" -> 200 to string(hash)
                "d.up.total" -> status to ""
                else -> error("Must not read $method after failed counter")
            }}.use { gateway ->
                val failure = assertThrows(RpcHttpStatusException::class.java) {
                    runBlocking { gateway.client().readSeedingEvidence(hash) }
                }
                assertEquals(status, failure.status)
                assertEquals(listOf("d.hash", "d.up.total"), gateway.methods)
            }
        }
    }

    @Test fun changedExactHashCannotAuthorizeAnObservedSeedingState() {
        val calls = AtomicInteger()
        Gateway { method -> 200 to when (method) {
            "d.hash" -> string(if (calls.incrementAndGet() == 1) hash else "cd".repeat(20))
            "d.up.total", "d.down.total" -> number(1_024)
            "d.complete", "d.is_active" -> number(1)
            "d.timestamp.finished" -> number(0)
            "d.throttle_name" -> string("")
            else -> error("Unexpected RPC $method")
        }}.use { gateway ->
            assertThrows(IllegalStateException::class.java) {
                runBlocking { gateway.client().readSeedingEvidence(hash) }
            }
            assertEquals(2, calls.get())
        }
    }

    @Test fun malformedActivityAndThrottleDoNotBecomeTrustedEvidence() {
        for ((badMethod, badBody) in listOf(
            "d.is_active" to number(2),
            "d.throttle_name" to string("x".repeat(129))
        )) {
            Gateway { method -> 200 to when (method) {
                "d.hash" -> string(hash)
                "d.up.total", "d.down.total" -> number(1_024)
                "d.complete" -> number(1)
                "d.is_active" -> if (badMethod == method) badBody else number(1)
                "d.timestamp.finished" -> number(0)
                "d.throttle_name" -> if (badMethod == method) badBody else string("")
                else -> error("Unexpected RPC $method")
            }}.use { gateway ->
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { gateway.client().readSeedingEvidence(hash) }
                }
                assertEquals(1, gateway.methods.count { it == "d.hash" })
            }
        }
    }
}
