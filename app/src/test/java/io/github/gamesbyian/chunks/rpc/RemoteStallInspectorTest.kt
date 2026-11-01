package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual HTTP/XML-RPC adapter, never a live account and never a mutation. */
class RemoteStallInspectorTest {
    private val hash = "ab".repeat(20)
    private val other = "cd".repeat(20)
    private fun value(inner: String) =
        "<methodResponse><params><param><value>$inner</value></param></params></methodResponse>"
    private fun number(n: Long) = value("<i8>$n</i8>")
    private fun string(s: String) = value("<string>$s</string>")
    private fun fault(code: Int, why: String) =
        """<methodResponse><fault><value><struct>
        <member><name>faultCode</name><value><int>$code</int></value></member>
        <member><name>faultString</name><value><string>$why</string></value></member>
        </struct></value></fault></methodResponse>"""

    private inner class Fixture(
        private val authRequired: Boolean = false,
        private val missingOptional: Boolean = false,
        private val busyOptional: Boolean = false,
        private val substituteFinalHash: Boolean = false
    ) : AutoCloseable {
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val methods: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val verbs: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val targets: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var hashReads = 0
        val address get() = "http://127.0.0.1:${server.address.port}/RPC2"

        init {
            server.createContext("/RPC2") { exchange ->
                verbs += exchange.requestMethod
                val payload = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(payload)?.groupValues?.get(1).orEmpty()
                methods += method
                targets += payload
                val authorized = "Basic " + Base64.getEncoder()
                    .encodeToString("alice:secret".toByteArray(Charsets.UTF_8))
                if (authRequired && exchange.requestHeaders.getFirst("Authorization") != authorized) {
                    exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"fixture\"")
                    exchange.sendResponseHeaders(401, -1)
                    exchange.close()
                } else {
                    val optional = method in setOf("d.hashing", "d.hashing_failed",
                        "d.is_hash_checking", "d.is_meta", "d.message")
                    val reply = when {
                        optional && busyOptional -> fault(-503, "Server busy")
                        optional && missingOptional -> fault(-32601, "Method not found")
                        method == "d.hash" -> {
                            hashReads++
                            string(if (substituteFinalHash && hashReads == 2) other else hash)
                        }
                        method == "d.state" -> number(1)
                        method == "d.is_active" -> number(0)
                        method == "d.is_open" -> number(0)
                        method == "d.hashing" -> number(0)
                        method == "d.hashing_failed" -> number(0)
                        method == "d.is_hash_checking" -> number(0)
                        method == "d.is_meta" -> number(1)
                        method == "d.message" ->
                            string("Permission denied /private/secret-path?passkey=NEVER-DISCLOSE")
                        else -> error("Unrecognized command $method")
                    }.toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(200, reply.size.toLong())
                    exchange.responseBody.use { it.write(reply) }
                }
            }
            server.start()
        }
        override fun close() = server.stop(0)
    }

    @Test fun stalledMagnetGetsSafeExactHashMetadataAndStorageEvidence() = runBlocking {
        Fixture().use { gateway ->
            val report = RemoteStallInspector.inspect(
                RTorrentRemote(RemoteCredentials(gateway.address, "", "")), hash)
            assertEquals(hash, report.hash)
            assertTrue(report.started)
            assertFalse(report.active)
            assertFalse(report.open)
            assertEquals(0, report.hashingPhase)
            assertEquals(true, report.awaitingMetadata)
            assertEquals(RemoteStallEvidence.MessageClass.STORAGE, report.messageClass)
            assertTrue(report.explanation().contains("storage-related"))
            assertFalse(report.toString().contains("passkey"))
            assertFalse(report.explanation().contains("private"))
            assertEquals(2, gateway.hashReads)
            assertEquals("d.hash", gateway.methods.first())
            assertEquals("d.hash", gateway.methods.last())
            assertEquals(10, gateway.methods.size)
            assertTrue(gateway.verbs.all { it == "POST" })
            assertTrue(gateway.targets.all { hash in it })
        }
    }

    @Test fun challengeOnReadsUsesOnlySafeGettersNotCredentialedHeadOrWritePreflight() = runBlocking {
        Fixture(authRequired = true).use { gateway ->
            val result = RemoteStallInspector.inspect(RTorrentRemote(
                RemoteCredentials(gateway.address, "alice", "secret")), hash)
            assertFalse(result.active)
            assertEquals(20, gateway.methods.size) // Each read is challenged once.
            assertFalse(gateway.methods.contains("system.client_version"))
            assertTrue(gateway.verbs.all { it == "POST" })
        }
    }

    @Test fun unsupportedOptionalMethodsRemainUnknownButOperationalFaultStopsImmediately() = runBlocking {
        Fixture(missingOptional = true).use { gateway ->
            val report = RemoteStallInspector.inspect(
                RTorrentRemote(RemoteCredentials(gateway.address, "", "")), hash)
            assertNull(report.hashingPhase)
            assertNull(report.hashCheckFailed)
            assertNull(report.hashChecking)
            assertNull(report.awaitingMetadata)
            assertEquals(RemoteStallEvidence.MessageClass.UNKNOWN, report.messageClass)
            assertEquals(2, gateway.hashReads)
        }
        Fixture(busyOptional = true).use { gateway ->
            val failure = assertThrows(RpcFault::class.java) {
                runBlocking {
                    RemoteStallInspector.inspect(
                        RTorrentRemote(RemoteCredentials(gateway.address, "", "")), hash)
                }
            }
            assertEquals("-503", failure.code)
            assertEquals(listOf("d.hash", "d.state", "d.is_active", "d.is_open",
                "d.hashing"), gateway.methods)
            assertEquals(1, gateway.hashReads)
        }
    }

    @Test fun changedIdentityCannotYieldStallEvidence() = runBlocking {
        Fixture(substituteFinalHash = true).use { gateway ->
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    RemoteStallInspector.inspect(
                        RTorrentRemote(RemoteCredentials(gateway.address, "", "")), hash)
                }
            }
            assertEquals(2, gateway.hashReads)
        }
    }

    @Test fun untrustedTrackerMessageNeverEscapesThroughCategoryOrSummary() {
        val raw = "Tracker failed https://private.example/?passkey=123 account/test"
        assertEquals(RemoteStallEvidence.MessageClass.TRACKER_OR_NETWORK,
            RemoteStallInspector.classify(raw))
        assertEquals(RemoteStallEvidence.MessageClass.NONE, RemoteStallInspector.classify(""))
        assertEquals(RemoteStallEvidence.MessageClass.OTHER,
            RemoteStallInspector.classify("private data without recognizable hint"))
        assertFalse(RemoteStallInspector.classify(raw).toString().contains("123"))
    }

    @Test fun malformedInfohashNeverContactsGateway() = runBlocking {
        Fixture().use { gateway ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    RemoteStallInspector.inspect(
                        RTorrentRemote(RemoteCredentials(gateway.address, "", "")), "../bad")
                }
            }
            assertTrue(gateway.methods.isEmpty())
        }
    }
}
